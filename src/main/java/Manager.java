import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.ec2.model.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class Manager {

    private static final AWS aws = AWS.getInstance();
    private ExecutorService executorService; 
    private Map<String, Integer> fileProcessingCount = new ConcurrentHashMap<>();
    private Map<String, String> urlMap = new ConcurrentHashMap<>();
    private ConcurrentLinkedQueue<Runnable> updateQueue = new ConcurrentLinkedQueue<>();


    public Manager() {
        this.executorService = Executors.newFixedThreadPool(2);
    }

    public static void main(String[] args) {
        Manager manager = new Manager();
        manager.run();
    }

    public void run() {
        try {
            while (true) {
                // Process the update queue
                Runnable updateTask;
                while ((updateTask = updateQueue.poll()) != null) {
                    updateTask.run();
                }

                // Check the job queue for incoming messages
                Message message = aws.getMessageFromQueue(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), 0);
                if (message != null) {
                    aws.deleteMessageFromQueue(Resources.APP_TO_MANAGER_QUEUE, message.receiptHandle());
                    if (message.body().equalsIgnoreCase("Terminate")) {
                        System.out.println("terminating...");
                        handleTerminateMessage();
                        break;
                    }
                    submitMessageTask(message);
                }

                // Check for worker results
                Message doneMessage = aws.getMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), 0);
                if (doneMessage != null) {
                    String[] parts = doneMessage.body().split("\t", 2);
                    aws.deleteMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), doneMessage.receiptHandle());
                    executorService.submit(() -> appendToFile(parts));
                    updateQueue.add(() -> {
                        int tasksLeft = fileProcessingCount.get(parts[0]) - 1;
                        fileProcessingCount.put(parts[0], tasksLeft);
                        if (tasksLeft == 0) {
                            sendSummaryFile(parts[0], true);
                        }
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void submitMessageTask(Message message) {
        executorService.submit(() -> {
            try {
                String[] msgDetails = message.body().split("\t");
                String inputfile = msgDetails[0];
                int workerCount = Integer.parseInt(msgDetails[1]);

                File temp = new File(inputfile + "_tempInputFile");
                aws.downloadFileFromS3(inputfile, temp, Resources.A1_BUCKET);

                File outputFile = new File(inputfile + "_tempOutputFile");
                List<String> messages = parseInputFile(temp, outputFile.getName());

                updateQueue.add(() -> {
                    fileProcessingCount.put(outputFile.getName(), messages.size());
                    urlMap.put(outputFile.getName(), inputfile);
                });

                int numWorkers = (int) Math.min(Math.ceil((double) messages.size() / workerCount), 9);
                startWorkerNodes(numWorkers);

                sendMessages(messages, Resources.MANAGER_TO_WORKER_QUEUE);
                deleteFile(temp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    private void deleteFile(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) {
                System.err.println("Failed to delete file: " + file.getAbsolutePath());
            }
        }
    }

    private void sendMessages(List<String> messages, String q) {
        try {
            // Loop through each string in the list and send it as a separate message to the SQS queue
            for (String message : messages) {
                // Create a SendMessageRequest for each message
                aws.sendSqsMessage(q, message);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }
    
    public synchronized void appendToFile(String[] parts) {
        // Create a File object for the HTML file
        File summaryFile = new File(parts[0]);
        // Use BufferedWriter to append content to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(summaryFile, true))) {
            // Append the content at the end of the file
            writer.write(parts[1]);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void sendSummaryFile(String fileName, boolean done) {
        try{
            // Create a File object for the HTML file
            File summaryS3File = new File(fileName);
            String originalFileUrl = urlMap.get(fileName);
            //Upload the summary to S3
            String summaryFilePath = aws.uploadFileToS3(fileName, summaryS3File, Resources.A1_BUCKET);
            //Send a message to the done queue: <originalFileName> \t <newFilePath>
            aws.sendSqsMessage(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE), originalFileUrl + '\t' + summaryFilePath + '\t' + done);
            // Delete temp summary file
            deleteFile(summaryS3File);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleTerminateMessage() {
        System.out.println("Received termination request. Shutting down...");
        executorService.shutdown();
        try {
            int currentWorkerCount = aws.getAllInstancesWithLabel(AWS.Label.Worker, true).size();
            List<String> terminateMessages = new ArrayList<>(Collections.nCopies(currentWorkerCount, "terminate"));
            sendMessages(terminateMessages, Resources.TERMINATE_QUEUE);

            //for each undone task, send the partial summaryfile back to the local app, informing that the manager has been terminated.
            for (Map.Entry<String, Integer> entry : fileProcessingCount.entrySet()) {
                String key = entry.getKey();
                Integer value = entry.getValue();
                if (value > 0) {
                    sendSummaryFile(key, false);
                }
            }

            //purge (clean) all queues before terminating (except terminate_queue and manager_to_app_queue) 
            purgeAllQueues();

            //wait for the sqs to be empty -> meaning that all workers got the terminate message and stopped their tasks
            while (true) {
                if (aws.getQueueSize(Resources.TERMINATE_QUEUE) == 0) {
                    System.out.println("Terminate Queue is empty. Terminating all workers...");
                    terminateAllWorkers();
                    System.out.println("Terminating Manager...");
                    aws.terminateInstance(aws.getAllInstancesWithLabel(AWS.Label.Manager, true).get(0).instanceId());
                    break;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void purgeAllQueues() {
        aws.purgeQueue(Resources.APP_TO_MANAGER_QUEUE);
        aws.purgeQueue(Resources.MANAGER_TO_WORKER_QUEUE);
        aws.purgeQueue(Resources.WORKER_TO_MANAGER_QUEUE);
    }


    private void terminateAllWorkers() throws InterruptedException{
        List<Instance> workers = aws.getAllInstancesWithLabel(AWS.Label.Worker, true);
        for(Instance instance : workers){
            aws.terminateInstance(instance.instanceId());
        }
    }
    
    //synchronized in order to prevent starting extra workers
    private synchronized void startWorkerNodes(int numWorkers) {
        try {
            List<Instance> runningInstances = aws.getAllInstancesWithLabel(AWS.Label.Worker, true);
            int currentWorkerCount = runningInstances.size();
            if (currentWorkerCount < numWorkers) {
                int workersToLaunch = numWorkers - currentWorkerCount;
                String userDataScript = "#!/bin/bash\n" +
                                        "aws s3 cp s3://" + Resources.A1_BUCKET + "/Ass_1-1.0-jar-with-dependencies.jar .\n" +
                                        "java -cp Ass_1-1.0-jar-with-dependencies.jar Worker";
                for (int i = 0; i < workersToLaunch; i++) {
                    RunInstancesResponse response = aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, InstanceType.T2_NANO, 1, 1, userDataScript);
                    String InstanceId = response.instances().get(0).instanceId();
                    aws.addTag(InstanceId, "Worker");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> parseInputFile(File inputFile, String fileName) throws IOException {
        List<String> parsedMessages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(inputFile.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                parsedMessages.add(fileName + '\t' + line);
            }
        }
        return parsedMessages;
    }

    public void checkAndReplaceUnhealthyInstances() {
        try {
            List<Instance> instances = aws.getAllInstancesWithLabel(AWS.Label.Worker, false);
            for(Instance instance : instances){
                if(aws.checkInstanceHealth(instance.instanceId()) == false){
                    System.out.println("Instance " + instance.instanceId() + " is unhealthy. Replacing...");
                    aws.terminateInstance(instance.instanceId());
                    String userDataScript = "#!/bin/bash\n" +
                                            "aws s3 cp s3://" + Resources.A1_BUCKET + "/Ass_1-1.0-jar-with-dependencies.jar .\n" +
                                            "java -cp Ass_1-1.0-jar-with-dependencies.jar Worker";
                RunInstancesResponse response = aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, InstanceType.T2_NANO, 1, 1, userDataScript);
                String InstanceId = response.instances().get(0).instanceId();
                aws.addTag(InstanceId, "Worker");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}