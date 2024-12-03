import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class App {
    final static AWS aws = AWS.getInstance();
    private String managerInstanceId;
    private String id = UUID.randomUUID().toString();

    public static void main(String[] args) {// args = [inFilePath, outFilePath, tasksPerWorker, -t (terminate, optional)]
        System.out.println("Starting App...");
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
            aws.sendSqsMessage(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), "terminate");
        }
    }

    public void setup() {
        aws.createBucketIfNotExists(Resources.INPUT_BUCKET);
        checkAndStartManagerNode();
        // Initialize SQS queues
        initializeQueues();
    }

   public void checkAndStartManagerNode() {
    try {
        // Get all instances with the "Manager" label
        List<Instance> managerInstances = aws.getAllInstancesWithLabel(AWS.Label.Manager);
        
        // Check if any of the manager instances are running
        boolean isManagerActive = managerInstances.stream()
            .anyMatch(instance -> instance.state().name() == InstanceStateName.RUNNING);
        
        if (!isManagerActive) {
            System.out.println("Creating Manager...");
            startManagerNode();  // No manager is running, start a new one
        } else {
            // Fetch the instance ID of the running manager node
            String managerInstanceId = managerInstances.stream()
                .filter(instance -> instance.state().name() == InstanceStateName.RUNNING)
                .map(Instance::instanceId)
                .findFirst()
                .orElse(null);
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
            String userDataScript = "wget https://edenuploadbucket.s3.us-east-1.amazonaws.com/Ass_1-1.0.jar && " + 
            "java -cp /home/ec2-user/Ass_1-1.0.jar Manager";
    
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
            System.out.println("Uploading file");
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist.");
            }
            // Upload file to S3
            String s3FileLocation = aws.uploadFileToS3(filePath, file, Resources.INPUT_BUCKET);
            System.out.println("File uploaded to S3 at: " + s3FileLocation);

            // Send file location to the file upload queue
            aws.sendSqsMessage(aws.getQueueUrl(Resources.APP_TO_MANAGER_QUEUE), appId + "\t" + 
                                                                    s3FileLocation + "\t" + 
                                                                        tasksPerWorker);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error uploading file or sending SQS message.");
        }
    }

    // Method to initialize the SQS queues if needed
    public void initializeQueues() {
        aws.createQueue(Resources.APP_TO_MANAGER_QUEUE);
        aws.createQueue(Resources.MANAGER_TO_APP_QUEUE);
    }

    // Poll the manager work status queue and download the file once it is processed
    public void pollManagerQueueAndDownloadFile(String inFilePath, String outFilePath, String appId) {
        boolean downloadCompleted = false;

        // Poll the queue for messages in a loop
        while (!downloadCompleted) {
            try {
                // Receive message from the manager work status queue
                Message msg = aws.getMessageFromQueue(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE), 0);

                if (!msg.body().isEmpty()) {
                    String[] msgParts = msg.body().split("\t");
                    String message = msgParts[0];
                    // Check if the message contains the id
                    if (message.equals(appId)) {
                        File outputFile = new File(outFilePath);
                        // If the message contains the file, download it from S3
                        aws.downloadFileFromS3(msgParts[2], outputFile, Resources.OUTPUT_BUCKET);
                        downloadCompleted = true;
                    }

                    // Delete the message from the queue after processing
                    aws.deleteMessageFromQueue(aws.getQueueUrl(Resources.MANAGER_TO_APP_QUEUE),
                            msg.receiptHandle());
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error receiving message from manager work status queue.");
            }
        }
    }

}
