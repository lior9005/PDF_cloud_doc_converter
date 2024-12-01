import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.sqs.model.*;
import java.io.File;
import java.util.UUID;


public class App {
    final static AWS aws = AWS.getInstance();
    private static final String appToManagerQueue = "appToManagerQueue";
    private static final String managerToAppQueue = "managerToAppQueue";
    Ec2Client ec2Client = Ec2Client.create();
    private String managerInstanceId;
    private String id = UUID.randomUUID().toString();

    public static void main(String[] args) {// args = [inFilePath, outFilePath, tasksPerWorker, -t (terminate, optional)]
        App app = new App();
        String inFilePath = args[0];
        String outFilePath = args[1];
        String tasksPerWorker = args[2];
        try {
            // Setting up the necessary services
            app.setup();
            // Upload a file to S3 and send a message to SQS
            app.uploadFileAndSendMessage(inFilePath, tasksPerWorker, app.id);

            // Poll the manager work status queue
            app.pollManagerQueueAndDownloadFile(inFilePath, outFilePath, app.id);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(args.length > 3 && args[3].equals("-t")) {
            aws.sendSqsMessage(aws.getQueueUrl(appToManagerQueue), "terminate");
        }
    }

    public void setup() {
        aws.createBucketIfNotExists("input-bucket");
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
            InstanceType instanceType = InstanceType.T2_MICRO;
            String userDataScript = "java -cp /home/ec2-user/Ass_1-1.0.jar Manager";
    
            // Launch manager node with a specific AMI and instance type
            RunInstancesResponse response = aws.runInstanceFromAmiWithScript(aws.IMAGE_AMI, instanceType, 1, 1, userDataScript);
            response.instances().forEach(instance -> managerInstanceId = instance.instanceId()); //just one instance
            System.out.println("Manager node started with ID: " + managerInstanceId);
    
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error starting Manager node.");
        }
    }

    public void uploadFileAndSendMessage(String filePath, String tasksPerWorker, String appId) {
        try {

            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist.");
            }
            // Upload file to S3
            String s3FileLocation = aws.uploadFileToS3(filePath, file, "inputBucket");
            System.out.println("File uploaded to S3 at: " + s3FileLocation);

            // Send file location to the file upload queue
            aws.sendSqsMessage(aws.getQueueUrl(appToManagerQueue), appId + "\t" + s3FileLocation + "\t" + tasksPerWorker);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error uploading file or sending SQS message.");
        }
    }

    // Method to initialize the SQS queues if needed
    public void initializeQueues() {
        aws.createQueue(appToManagerQueue);
        aws.createQueue(managerToAppQueue);
    }

    // Poll the manager work status queue and download the file once it is processed
    public void pollManagerQueueAndDownloadFile(String inFilePath, String outFilePath, String appId) {
        boolean downloadCompleted = false;

        // Poll the queue for messages in a loop
        while (!downloadCompleted) {
            try {
                // Receive message from the manager work status queue
                Message msg = aws.getMessageFromQueue(aws.getQueueUrl(managerToAppQueue), 0);

                if (!msg.body().isEmpty()) {
                    // Extract the message and check if it matches the uploaded file path
                    String message = msg.body();

                    // Check if the message contains the filename or file path you uploaded
                    if (message.equals(inFilePath)) {
                        File outputFile = new File(outFilePath);
                        // If the message contains the file, download it from S3
                        aws.downloadFileFromS3(message, outputFile, "outputBucket");
                        downloadCompleted = true;
                    }

                    // Delete the message from the queue after processing
                    aws.deleteMessageFromQueue(aws.getQueueUrl(managerToAppQueue),
                            msg.receiptHandle());
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error receiving message from manager work status queue.");
            }
        }
    }

}
