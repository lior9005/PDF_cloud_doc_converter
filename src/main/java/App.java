import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.*;
import java.io.File;
import java.nio.file.Paths;

public class App {
    final static AWS aws = AWS.getInstance();
    private static final String fileUploadQueueName = "fileUploadQueue";
    private static final String managerWorkStatusQueueName = "managerWorkStatusQueue";
    Ec2Client ec2Client = Ec2Client.create();
    private String managerInstanceId;

    public static void main(String[] args) {// args = [inFilePath, outFilePath, tasksPerWorker, -t (terminate, optional)]
        App app = new App();
        String inFilePath = args[0];
        String outFilePath = args[1];
        String tasksPerWorker = args[2];
        String terminate = args[3];

        try {
            // Setting up the necessary services
            app.setup();
            // Upload a file to S3 and send a message to SQS
            app.uploadFileAndSendMessage(inFilePath, tasksPerWorker);

            // Poll the manager work status queue
            app.pollManagerQueueAndDownloadFile(inFilePath, outFilePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(terminate.equals("-t")) {
            aws.terminateInstance(app.managerInstanceId);
        }
    }

    public void setup() {
        aws.createBucketIfNotExists(aws.bucketName);
        checkAndStartManagerNode();
        // Initialize SQS queues
        initializeQueues();
    }

    public void checkAndStartManagerNode() {
        try {
            DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder()
                .filters(
                    Filter.builder().name("tag:Role").values("Manager").build()
                )
                .build();
    
            DescribeInstancesResponse response = ec2Client.describeInstances(describeInstancesRequest);
    
            boolean isManagerActive = response.reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .anyMatch(instance -> {
                    if (instance.state().name() == InstanceStateName.RUNNING) {
                        managerInstanceId = instance.instanceId();  // Store the instance ID
                        return true;
                    }
                    return false;
                });
    
            if (!isManagerActive) {
                startManagerNode();  // No manager is running, start a new one
            } else {
                System.out.println("Manager node is already running with ID: " + managerInstanceId);
            }
    
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error checking Manager node status.");
        }
    }
    
    // Modify the startManagerNode method
    public void startManagerNode() {
        try {
            String ami = aws.IMAGE_AMI;
            InstanceType instanceType = InstanceType.T2_MICRO;
            String userDataScript = "#!/bin/bash\n# Manager node initialization script";
    
            // Launch manager node with a specific AMI and instance type
            aws.runInstanceFromAmiWithScript(ami, instanceType, 1, 1, userDataScript);
            System.out.println("Manager node started.");
    
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error starting Manager node.");
        }
    }

    public void uploadFileAndSendMessage(String filePath, String tasksPerWorker) {
        try {

            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist.");
            }
            // Upload file to S3
            String s3Path = aws.uploadFileToS3(filePath, file);
            System.out.println("File uploaded to S3 at: " + s3Path);

            // Send file location to the file upload queue
            aws.sendSqsMessage(aws.getQueueUrl(fileUploadQueueName), s3Path + "\t" + tasksPerWorker);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error uploading file or sending SQS message.");
        }
    }

    // Method to initialize the SQS queues if needed
    public void initializeQueues() {
        aws.createQueue(fileUploadQueueName);
        aws.createQueue(managerWorkStatusQueueName);
    }

    // Poll the manager work status queue and download the file once it is processed
    public void pollManagerQueueAndDownloadFile(String inFilePath, String outFilePath) {
        boolean downloadCompleted = false;

        // Poll the queue for messages in a loop
        while (!downloadCompleted) {
            try {
                // Receive message from the manager work status queue
                Message msg = aws.getMessageFromQueue(aws.getQueueUrl(managerWorkStatusQueueName), 1);

                if (!msg.body().isEmpty()) {
                    // Extract the message and check if it matches the uploaded file path
                    String message = msg.body();

                    // Check if the message contains the filename or file path you uploaded
                    if (message.equals(inFilePath)) {
                        File outputFile = new File(outFilePath);
                        // If the message contains the file, download it from S3
                        aws.downloadFileFromS3(message, outputFile);
                        downloadCompleted = true;
                    }

                    // Delete the message from the queue after processing
                    aws.deleteMessageFromQueue(aws.getQueueUrl(managerWorkStatusQueueName),
                            msg.receiptHandle());
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error receiving message from manager work status queue.");
            }
        }
    }

}
