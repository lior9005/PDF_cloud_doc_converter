import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.ec2.model.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class Manager {

    private static final AWS aws = AWS.getInstance();
    private ExecutorService executorService; 
    private Map<String, Integer> fileProcessingCount = new HashMap<>();
    private Map<String, String> urlMap = new HashMap<>();

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
                System.out.println("checking messages from clients");
                Message message = aws.getMessageFromQueue(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), 0);
                
                if (message != null) {
                    if (message.body().equalsIgnoreCase("Terminate")) { /////////////////////////////
                        System.out.println("terminating...");
                        handleTerminateMessage();
                        break;
                    }
                    submitMessageTask(message);
                }
                System.out.println("checking messages from workers");
                Message doneMessage = aws.getMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), 0);
                if (doneMessage != null) {
                    //divide message to outfileurl : actualmessage
                    String[] parts = doneMessage.body().split("\t", 2);
                    //add message to the corresponding file
                    appendToFile(parts);
                    //delete message from queue so the message will not be written again
                    aws.deleteMessageFromQueue(aws.getQueueUrl(Resources.WORKER_TO_MANAGER_QUEUE), doneMessage.receiptHandle());
                    //update the map in order to know how many tasks left for file
                    fileProcessingCount.replace(parts[0], fileProcessingCount.get(parts[0]) - 1);
                    //if all tasks completed, send summaryfile to app queue
                    if(fileProcessingCount.get(parts[0]) == 0){
                        sendSummaryFile(parts[0]);
                        fileProcessingCount.remove(parts[0]);
                    }
                }
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
                File temp = new File("tempFile");
                aws.downloadFileFromS3(inputfile, temp, Resources.INPUT_BUCKET);

                File outputFile = new File(inputfile + "TempOutput");
                List<String> messages = new ArrayList<>();
                messages = parseInputFile(temp, outputFile.getName());
                fileProcessingCount.put(outputFile.getName(), messages.size());
                urlMap.put(outputFile.getName(), inputfile);
                
                // Create worker nodes
                int numWorkers = Math.min(messages.size()/workerCount, 9);
                startWorkerNodes(numWorkers);

                // Send tasks to worker queue.
                sendMessages(messages, Resources.MANAGER_TO_WORKER_QUEUE);

                // Delete the job message from the job queue
                aws.deleteMessageFromQueue(Resources.APP_TO_MANAGER_QUEUE, message.receiptHandle());
            } catch (Exception e) {

                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        });
    }
    
    private void sendMessages(List<String> messages, String q) {
        try {
            // Loop through each string in the list and send it as a separate message to the SQS queue
            for (String message : messages) {
                // Create a SendMessageRequest for each message
                aws.sendSqsMessage(Resources.MANAGER_TO_WORKER_QUEUE, message);
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
        } catch (Exception e) {
            e.printStackTrace();  // Handle potential IO exceptions
        }
    }

    //finished
    private void handleTerminateMessage() {
        System.out.println("Received termination request. Shutting down...");
        executorService.shutdownNow();
        try {
            int currentWorkerCount = aws.getAllInstancesWithLabel(AWS.Label.Worker).size();
            System.out.println("There are " + currentWorkerCount + "workers to terminate");
            List<String> terminateMessages = new ArrayList<>(Collections.nCopies(currentWorkerCount, "terminate"));
            sendMessages(terminateMessages, Resources.TERMINATE_QUEUE);
            while (true) {
                if (aws.getQueueSize(Resources.TERMINATE_QUEUE) == 0) {
                    System.out.println("Terminate Queue is empty. Terminating all workers...");
                    terminateAllWorkers();
                    System.out.println("Number of workers alive: " + aws.getAllInstancesWithLabel(AWS.Label.Worker).size());
                    System.out.println("Terminating Manager...");
                    aws.terminateInstance(aws.getAllInstancesWithLabel(AWS.Label.Manager).get(0).instanceId());
                    break;
                }
                System.out.println("Waiting for " + aws.getQueueSize(Resources.TERMINATE_QUEUE) + "workers to finish their current jobs. Waiting...");
                Thread.sleep(100); // Poll every 0.1 seconds
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void terminateAllWorkers() throws InterruptedException{
        List<Instance> workers = aws.getAllInstancesWithLabel(AWS.Label.Worker);
        for(Instance instance : workers){
            aws.terminateInstance(instance.instanceId());
        }
    }
    
    //update the startupscript that runs worker
    private synchronized void startWorkerNodes(int numWorkers) {
        try {
            List<Instance> runningInstances = aws.getAllInstancesWithLabel(AWS.Label.Worker);
            int currentWorkerCount = runningInstances.size();

            if (currentWorkerCount < numWorkers) {
                int workersToLaunch = numWorkers - currentWorkerCount;
                String userDataScript = "#!/bin/bash\n" +
                "aws s3 cp s3://eden-input-test-bucket/Ass_1-1.0-jar-with-dependencies.jar .\n" +
                "java -cp /home/ec2-user/Ass_1-1.0-jar-with-dependencies.jar Worker";
                for (int i = 0; i < workersToLaunch; i++) {
                    RunInstancesResponse response = aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, InstanceType.T2_NANO, 1, 1, userDataScript);
                    String InstanceId = response.instances().get(0).instanceId();
                    aws.addTag(InstanceId, "Worker");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
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

}