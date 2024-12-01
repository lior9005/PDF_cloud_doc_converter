import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.ec2.model.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class Manager {
    private static final String appToManagerQueue = "appToManagerQueue";
    private static final String WORKERS_JOB_QUEUE = "workers-job-queue";
    private static final String managerToAppQueue = "managerToAppQueue";
    private static final String WORKERS_DONE_QUEUE = "workers-done-queue";
    private static final String TERMINATE_QUEUE = "terminate-queue";
    private static final String INPUT_BUCKET = "outputBucket"; 
    private static final String OUTPUT_BUCKET = "inputBucket"; 

    private static final AWS aws = AWS.getInstance();
    private ExecutorService executorService; 
    private Map<String, Integer> fileProcessingCount = new HashMap<>();

    public Manager() {
        this.executorService = Executors.newFixedThreadPool(2);
    }

    public static void main(String[] args) {
        Manager manager = new Manager();
        manager.run();
    }

    public void run() {
        try {
            String jobQueueUrl = aws.getQueueUrl(appToManagerQueue);
            String workersDoneQueueUrl = aws.getQueueUrl(WORKERS_DONE_QUEUE);
            while (true) {
                // Step 1: Check the job queue for incoming messages
                Message message = aws.getMessageFromQueue(jobQueueUrl, 0);

                if (message != null) {
                    if (message.body().equalsIgnoreCase("Terminate")) { /////////////////////////////
                        handleTerminateMessage();
                        break;
                    }
                    submitMessageTask(message);
                }
                Message doneMessage = aws.getMessageFromQueue(workersDoneQueueUrl, 0);
                if (message != null) {
                    String[] parts = doneMessage.body().split("\t");
                    appendToHtmlFile(parts[0], parts[1]);  // Update HTML with worker results.
                    aws.deleteMessageFromQueue(workersDoneQueueUrl, doneMessage.receiptHandle());
                    fileProcessingCount.replace(parts[0], fileProcessingCount.get(parts[0]) - 1);
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

    private void submitMessageTask(Message message){
        executorService.submit(() -> {
            String[] msgDetails = message.body().split("\t");
            String fileLocation = msgDetails[1];
            int workerCount = Integer.parseInt(msgDetails[2]);

            // Download the file and send tasks to the worker job queue.
            File inputFile = new File("inputFile");
            aws.downloadFileFromS3(fileLocation, inputFile, INPUT_BUCKET);

            File htmlFile = createEmptyHtmlFile(fileLocation);
            List<String> messages = new ArrayList<>();
            try {
                messages = parseInputFile(inputFile, htmlFile.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }

            fileProcessingCount.put(htmlFile.getName(), messages.size());
            
            // Create worker nodes
            int numWorkers = Math.min(messages.size()/workerCount, 9);
            startWorkerNodes(numWorkers);

            // Send tasks to worker queue.
            sendMessages(messages, WORKERS_JOB_QUEUE);

            // Delete the job message from the job queue
            aws.deleteMessageFromQueue(appToManagerQueue, message.receiptHandle());
        });
    }

    private void sendMessages(List<String> messages, String q) {
        try {
            // Loop through each string in the list and send it as a separate message to the SQS queue
            for (String message : messages) {
                // Create a SendMessageRequest for each message
                aws.sendSqsMessage(WORKERS_JOB_QUEUE, message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void appendToHtmlFile(String fileName, String contentToAdd) {
            // Create a File object for the HTML file
            File htmlFile = new File(fileName);

            // Use BufferedWriter to append content to the file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(htmlFile, true))) {
                // Append the content at the end of the file
                writer.write(contentToAdd);
                writer.newLine();  // Optionally add a new line after the content
            } catch (IOException e) {
                e.printStackTrace();  // Handle potential IO exceptions
            }
        }

    public static void sendSummaryFile(String fileName) {
        try{
            // Create a File object for the HTML file
            File htmlFile = new File(fileName);
            //Upload the summary to S3
            String summaryFilePath = aws.uploadFileToS3(fileName, htmlFile, OUTPUT_BUCKET);
            // Step 10: Send a message to the done queue: <originalFileName> \t <newFilePath>
            aws.sendSqsMessage(aws.getQueueUrl(managerToAppQueue), fileName.replace("output.html", ".pdf")+ '\t' + summaryFilePath);
        } catch (Exception e) {
            e.printStackTrace();  // Handle potential IO exceptions
        }
    }

    // Create an empty HTML file with the same name as the PDF, appending "output"
    private File createEmptyHtmlFile(String fileLocation) {
        String fileName = extractFileNameFromUrl(fileLocation); // Extract the filename from the URL
        String htmlFileName = fileName.replace(".pdf", "output.html"); // Replace .pdf with output.html
        File htmlFile = new File(htmlFileName);
        return htmlFile;
    }

    // Extract the filename from the URL (without the path)
    private String extractFileNameFromUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            String path = parsedUrl.getPath();
            return Paths.get(path).getFileName().toString(); // Get the file name from the path
        } catch (Exception e) {
            e.printStackTrace();
            return "unknown_filename.pdf"; // Default fallback name if URL parsing fails
        }
    }

    private void handleTerminateMessage() {
        System.out.println("Received termination request. Shutting down...");
        int currentWorkerCount = 0;
        try {
            currentWorkerCount = aws.getAllInstancesWithLabel(AWS.Label.Worker).size();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
        List<String> terminateMessages = new ArrayList<>(Collections.nCopies(currentWorkerCount, "terminate"));
        sendMessages(terminateMessages, TERMINATE_QUEUE);
        try {
            while (true) {
                if (aws.getQueueSize(TERMINATE_QUEUE) == 0) {
                    System.out.println("Terminate Queue is empty. Terminating all workers...");
                    terminateAllWorkers();
                    aws.terminateInstance(aws.getAllInstancesWithLabel(AWS.Label.Manager).get(0).instanceId());
                    break;
                }
                System.out.println("Waiting for workers to finish their current jobs. Waiting...");
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
                String startupScript = "wget https://edenuploadbucket.s3.us-east-1.amazonaws.com/Ass_1-1.0.jar &&" +
                "java -cp /home/ec2-user/Ass_1-1.0.jar Worker"; 
                for (int i = 0; i < workersToLaunch; i++) {
                    aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, InstanceType.T2_NANO, 1, 1, startupScript);
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
                String[] parts = line.split("\\s+"); // Split by whitespace (operation URL)
                if (parts.length == 2) {
                    String operation = parts[0]; // First part is the operation
                    String url = parts[1]; // Second part is the URL
                    String operationMessage = fileName + '\t' + operation + '\t' + url;
                    parsedMessages.add(operationMessage); // Add message to list
                }
            }
        }
        return parsedMessages;
    }

}