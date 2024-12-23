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

    public Manager() {
        this.executorService = Executors.newFixedThreadPool(2);
    }

    public static void main(String[] args) {
        Manager manager = new Manager();
        manager.run();
    }

    //finished
    public void run() {
        try {
            while (true) {
                // Step 1: Check the job queue for incoming messages
                Message message = aws.getMessageFromQueue(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), 0);

                if (message != null) {
                    // Delete the job message from the job queue
                    aws.deleteMessageFromQueue(Resources.APP_TO_MANAGER_QUEUE, message.receiptHandle());
                    if (message.body().equalsIgnoreCase("Terminate")) { /////////////////////////////
                        System.out.println("terminating...");
                        handleTerminateMessage();
                        break;
                    }
                    submitMessageTask(message);
                }

                Message doneMessage = aws.getMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), 0);
                if (doneMessage != null) {
                    //divide message to outfileurl : actualmessage
                    String[] parts = doneMessage.body().split("\t", 2);
                    //add message to the corresponding file
                    appendToFile(parts);
                    //delete message from queue so the message will not be written again
                    aws.deleteMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), doneMessage.receiptHandle());
                    //update the map in order to know how many tasks left for file
                    System.out.println("fileName" + parts[0]);
                    System.out.println("map keys");
                    for (Map.Entry<String, Integer> entry : fileProcessingCount.entrySet()) {
                        System.out.println(entry.getKey() + " : " + entry.getValue());
                    }
                    int tasksLeft = fileProcessingCount.get(parts[0]) - 1;
                    fileProcessingCount.put(parts[0], tasksLeft);
                    //if all tasks completed, send summaryfile to app queue
                    if(fileProcessingCount.get(parts[0]) == 0){
                        sendSummaryFile(parts[0]);
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //finished
    private void submitMessageTask(Message message){
        executorService.submit(() -> {
            try{
                String[] msgDetails = message.body().split("\t");
                String inputfile = msgDetails[0];
                int workerCount = Integer.parseInt(msgDetails[1]);

                // Download the file and send tasks to the worker job queue.
                File temp = new File(inputfile + "_tempInputFile");
                aws.downloadFileFromS3(inputfile, temp, Resources.INPUT_BUCKET);

                File outputFile = new File(inputfile + "_tempOutputFile");
                List<String> messages = new ArrayList<>();
                messages = parseInputFile(temp, outputFile.getName());
                fileProcessingCount.put(outputFile.getName(), messages.size());
                urlMap.put(outputFile.getName(), inputfile);
                
                // Create worker nodes
                int numWorkers = (int) Math.min(Math.ceil((double)messages.size()/workerCount), 9);
                startWorkerNodes(numWorkers);

                // Send tasks to worker queue.
                sendMessages(messages, Resources.MANAGER_TO_WORKER_QUEUE);

                // Delete temp file
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
    
    //finished
    public void appendToFile(String[] parts) {
        // Create a File object for the HTML file
        File summaryFile = new File(parts[0]);
        // Use BufferedWriter to append content to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(summaryFile, true))) {
            // Append the content at the end of the file
            writer.write(parts[1]);
            writer.newLine();  // Optionally add a new line after the content
        } catch (IOException e) {
            e.printStackTrace();  // Handle potential IO exceptions
        }
    }
    
    //finished
    public void sendSummaryFile(String fileName) {
        try{
            // Create a File object for the HTML file
            File summaryS3File = new File(fileName);
            String originalFileUrl = urlMap.get(fileName);
            //Upload the summary to S3
            String summaryFilePath = aws.uploadFileToS3(fileName, summaryS3File, Resources.OUTPUT_BUCKET);
            // Step 10: Send a message to the done queue: <originalFileName> \t <newFilePath>
            aws.sendSqsMessage(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE), originalFileUrl + '\t' + summaryFilePath);
            // Delete temp summary file
            deleteFile(summaryS3File);
        } catch (Exception e) {
            e.printStackTrace();  // Handle potential IO exceptions
        }
    }

    //finished
    private void handleTerminateMessage() {
        System.out.println("Received termination request. Shutting down...");
        executorService.shutdownNow();
        try {
            int currentWorkerCount = aws.getAllInstancesWithLabel(AWS.Label.Worker, true).size();
            List<String> terminateMessages = new ArrayList<>(Collections.nCopies(currentWorkerCount, "terminate"));
            sendMessages(terminateMessages, Resources.TERMINATE_QUEUE);
            while (true) {
                if (aws.getQueueSize(Resources.TERMINATE_QUEUE) == 0) {
                    System.out.println("Terminate Queue is empty. Terminating all workers...");
                    terminateAllWorkers();
                    System.out.println("Terminating Manager...");
                    aws.terminateInstance(aws.getAllInstancesWithLabel(AWS.Label.Manager, true).get(0).instanceId());
                    break;
                }
                Thread.sleep(100); // Poll every 0.1 seconds
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void terminateAllWorkers() throws InterruptedException{
        List<Instance> workers = aws.getAllInstancesWithLabel(AWS.Label.Worker, true);
        for(Instance instance : workers){
            aws.terminateInstance(instance.instanceId());
        }
    }
    
    //update the startupscript that runs worker
    private synchronized void startWorkerNodes(int numWorkers) {
        try {
            List<Instance> runningInstances = aws.getAllInstancesWithLabel(AWS.Label.Worker, true);
            int currentWorkerCount = runningInstances.size();
            if (currentWorkerCount < numWorkers) {
                int workersToLaunch = numWorkers - currentWorkerCount;
                String userDataScript = "#!/bin/bash\n" +
                "aws s3 cp s3://eden-input-test-bucket/Ass_1-1.0-jar-with-dependencies.jar .\n" +
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
                // Add message to list
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
                "aws s3 cp s3://eden-input-test-bucket/Ass_1-1.0-jar-with-dependencies.jar .\n" +
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