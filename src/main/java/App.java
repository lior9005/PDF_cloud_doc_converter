import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class App {
    final static AWS aws = AWS.getInstance();
    private String managerInstanceId;
    private String s3OriginalURL = "";
    private File summaryFile;
    private String inFilePath = "";

    // args = [inFilePath, outFilePath, tasksPerWorker, -t (terminate - optional)]
    public static void main(String[] args) {
        if(args.length < 3) {
            System.out.println("Invalid start script - missing variables");
        }
        else{
            App app = new App();
            app.inFilePath = args[0];
            String outFilePath = args[1];
            String tasksPerWorker = args[2];
            app.summaryFile = new File(outFilePath + "_tmp");
            try {
                // Setting up the necessary services
                app.setup();
                // Upload a file to S3 and send a message to SQS
                app.upload();

                // Send file location to the file upload queue. Format: originalfileURL \t n
                aws.sendSqsMessage(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), app.inFilePath + "\t" + tasksPerWorker);

                // Poll the manager work status queue
                app.pollManagerQueueAndDownloadFile();

                // Create an HTML file from the downloaded file
                app.createHtmlFromDownloadedFile(outFilePath);

                // Delete the temporary downloaded file
                app.summaryFile.delete();

            } catch (Exception e) {
                e.printStackTrace();
            }
            if(args.length > 3 && args[3].equals("-t")) {
                aws.sendSqsMessage(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), "terminate");
            }
        }
    }

    public void setup() {
        aws.createBucketIfNotExists(Resources.A1_BUCKET);
        aws.createPublicBucketIfNotExists(Resources.PUBLIC_BUCKET);
        checkAndStartManagerNode();
        initializeQueues();
    }

    public void checkAndStartManagerNode() {
        try {
            System.out.println("Checking for running manager...");
    
            // Get all instances with the "Manager" label
            List<Instance> managerInstances = aws.getAllInstancesWithLabel(AWS.Label.Manager, false);
    
            boolean hasActiveManager = managerInstances.stream()
                .anyMatch(instance -> {
                    InstanceStateName stateName = instance.state().name();
                    return stateName == InstanceStateName.RUNNING || stateName == InstanceStateName.PENDING;
                });
    
            if (!hasActiveManager) {
                startManagerNode(); // No active manager, start a new one
            } else {
                // Fetch the instance ID of the running or initializing manager node
                String managerInstanceId = managerInstances.stream()
                    .filter(instance -> instance.state().name() == InstanceStateName.RUNNING || instance.state().name() == InstanceStateName.PENDING)
                    .map(Instance::instanceId)
                    .findFirst()
                    .orElse(null);
                System.out.println("Manager node is already running or initializing with ID: " + managerInstanceId);
            }
    
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error checking Manager node status.");
        }
    }
    
    public void startManagerNode() {
        try {
            InstanceType instanceType = InstanceType.T2_MICRO;
            String userDataScript = "#!/bin/bash\n" +
                                    "aws s3 cp s3://" + Resources.A1_BUCKET + "/Ass_1-1.0-jar-with-dependencies.jar .\n" +
                                    "java -cp Ass_1-1.0-jar-with-dependencies.jar Manager";
    
            // Launch manager node with a specific AMI and instance type
            RunInstancesResponse response = aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, instanceType, 1, 1, userDataScript);
            managerInstanceId = response.instances().get(0).instanceId();
            aws.addTag(managerInstanceId, "Manager");
            System.out.println("Manager node started with ID: " + managerInstanceId);
    
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error starting Manager node.");
        }
    }

    public void upload() {
        try {
            System.out.println("Uploading file");

            File file = new File(inFilePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist.");
            }
            // Upload file to S3
            s3OriginalURL = aws.uploadFileToS3(inFilePath, file, Resources.A1_BUCKET);
            System.out.println("File uploaded to S3 at: " + s3OriginalURL);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error uploading file.");
        }
    }

    public void initializeQueues() {
        aws.createQueue(Resources.APP_TO_MANAGER_QUEUE);
        aws.createQueue(Resources.MANAGER_TO_APP_QUEUE);
        aws.createQueue(Resources.MANAGER_TO_WORKER_QUEUE);
        aws.createQueue(Resources.WORKER_TO_MANAGER_QUEUE);
        aws.createQueue(Resources.TERMINATE_QUEUE);
    }

    // Poll the manager work status queue and download the file once it is processed
    public void pollManagerQueueAndDownloadFile() {
        boolean downloadCompleted = false;

        // Poll the queue for messages in a loop
        while (!downloadCompleted) {
            try {
                // Receive message from the manager work status queue
                Message msg = aws.getMessageFromQueue(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE), 0);
                if (msg != null && !msg.body().isEmpty()) {
                    // Extract the message and check if it matches the uploaded file path
                    String message = msg.body();
                    //messageParts[0] = originalURL ; messageParts[1] = summaryfileURL
                    String[] messageParts = message.split("\t");
                    if (messageParts[0].equals(inFilePath)) {
                        if (messageParts.length == 3) {    
                            if(messageParts[2].equals("false")){
                                System.out.println("Manager was terminated by another app, output file recieved is partial");
                            }
                            //download summary file
                            aws.downloadFileFromS3(inFilePath + "_tempOutputFile", summaryFile, Resources.A1_BUCKET); 
                            // Mark the operation as complete
                            downloadCompleted = true;
                            // Delete the message from the queue
                            aws.deleteMessageFromQueue(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE),
                                    msg.receiptHandle());
                        } else {
                            System.out.println("Invalid message format. Skipping...");
                            aws.releaseMessageToQueue(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE), msg.receiptHandle());
                        }
                    }
                }
                else{
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error receiving message from manager work status queue.");
            }
        }
    }

    private void createHtmlFromDownloadedFile(String outFilePath) throws Exception {
        File htmlFile = new File(outFilePath);
        try (BufferedReader reader = Files.newBufferedReader(summaryFile.toPath());
             BufferedWriter writer = Files.newBufferedWriter(htmlFile.toPath())) {
    
            writer.write("<!DOCTYPE html>\n<html>\n<body>\n");
    
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length == 3) {
                    String operation = parts[0];
                    String oldUrl = parts[1];
                    String newUrl = parts[2];
                    
                    int index = newUrl.indexOf("result");
                    String urlTitle = newUrl;
                    if(index != -1){
                        urlTitle = newUrl.substring(0, index + "result".length());
                    }

                    writer.write("<p>" + operation + ": ");
                    writer.write("<a href='" + oldUrl + "'>" + oldUrl + "</a>\t");
                    writer.write(" <a href='" + newUrl + "'>" + urlTitle + "</a>\n");
                }
            }
    
            writer.write("</body>\n</html>\n");
            System.out.println("HTML file generated successfully: " + htmlFile.getAbsolutePath());
    
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
