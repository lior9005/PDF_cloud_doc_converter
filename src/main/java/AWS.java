import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class AWS {

    public final String IMAGE_AMI = "ami-0e338d65c027b1b94";
    public static Region region1 = Region.US_EAST_1;
    public static Region region2 = Region.US_WEST_2;
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;
    private static AWS instance = null;

    private AWS() {
        s3 = S3Client.builder().region(region2).build();
        sqs = SqsClient.builder().region(region1).build();
        ec2 = Ec2Client.builder().region(region1).build();
    }

    public static AWS getInstance() {
        if (instance == null) {
            return new AWS();
        }

        return instance;
    }


//////////////////////////////////////////  EC2

    public void runInstanceFromAMI(String ami) {
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(ami)
                .instanceType(InstanceType.T2_MICRO)
                .minCount(1)
                .maxCount(5) // todo decide what to put here
                .build();

        // Launch the instance
        try {
            ec2.runInstances(runInstancesRequest);
        } catch (Ec2Exception ignored) {
        }
    }

    public RunInstancesResponse runInstanceFromAmiWithScript(String ami, InstanceType instanceType, int min, int max, String script) {
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(ami)
                .instanceType(instanceType)
                .iamInstanceProfile(iam -> iam.name("LabInstanceProfile"))
                .minCount(min)
                .maxCount(max)
                .userData(Base64.getEncoder().encodeToString(script.getBytes()))
                // @ADD security feratures
                .build();

        // Launch the instance
        try {
            return ec2.runInstances(runInstancesRequest);
        } catch (Ec2Exception e) {
            throw e;
        }
    }

    public List<Instance> getAllInstances() {
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder().build();

        DescribeInstancesResponse describeInstancesResponse = null;
        try {
            describeInstancesResponse = ec2.describeInstances(describeInstancesRequest);
        } catch (Ec2Exception ignored) {
        }

        return describeInstancesResponse.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .toList();
    }

    public List<Instance> getAllInstancesWithLabel(Label label, boolean running) throws InterruptedException {
        DescribeInstancesRequest describeInstancesRequest = null;
        if(running){
            describeInstancesRequest =
            DescribeInstancesRequest.builder()
                    .filters(Filter.builder()
                            .name("tag:Label")
                            .values(label.toString())
                            .build(),
                            Filter.builder()
                            .name("instance-state-name")
                            .values("running")
                            .build())
                    .build();
        }
        else{
            describeInstancesRequest =
            DescribeInstancesRequest.builder()
                    .filters(Filter.builder()
                            .name("tag:Label")
                            .values(label.toString())
                            .build())
                    .build();
        }

        DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(describeInstancesRequest);

        return describeInstancesResponse.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .collect(Collectors.toList());
    }

    public void terminateInstance(String instanceId) {
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

        // Terminate the instance
        try {
            ec2.terminateInstances(terminateRequest);
        } catch (Ec2Exception e) {
            throw e;
        }
    }

    public CreateTagsResponse addTag(String instanceId, String label){
        Tag tag = Tag.builder()
                        .key("Label")
                        .value(label)
                        .build();
        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag)
                .build();
	    return ec2.createTags(tagRequest);
    }


    public boolean checkInstanceHealth(String instanceId) {
        // Create the request to describe the instance status
        DescribeInstanceStatusRequest describeInstanceStatusRequest = DescribeInstanceStatusRequest.builder()
                .instanceIds(instanceId)  // Specify the instance ID
                .build();
        try {
            // Fetch the instance status
            DescribeInstanceStatusResponse describeInstanceStatusResponse = ec2.describeInstanceStatus(describeInstanceStatusRequest);
            
            // Ensure there's at least one instance status in the response
            if (describeInstanceStatusResponse.instanceStatuses().isEmpty()) {
                System.err.println("No status information available for the instance.");
                return false;
            }
    
            // Get the instance status
            InstanceStatus instanceStatus = describeInstanceStatusResponse.instanceStatuses().get(0);
    
            // Check if the status values are OK
            boolean isSystemStatusOk = instanceStatus.systemStatus().status().toString().equals("ok");
            boolean isInstanceStatusOk = instanceStatus.instanceStatus().status().toString().equals("ok");
    
            return isSystemStatusOk && isInstanceStatusOk;
        } catch (Ec2Exception e) {
            System.err.println("Error checking instance health: " + e.getMessage());
            return false;
        }
    }
    ////////////////////////////// S3

    public String uploadFileToS3(String keyPath, File file, String bucketName) throws Exception {
        System.out.printf("Start upload: %s, to S3\n", file.getName());

        PutObjectRequest req =
                PutObjectRequest.builder()
                
                .bucket(bucketName)
                        .key(keyPath)
                        .build();

        s3.putObject(req, file.toPath()); // we don't need to check if the file exist already
        // Return the S3 path of the uploaded file
        return "https://" + bucketName + ".s3.us-west-2.amazonaws.com/" + keyPath;
    }

    public void downloadFileFromS3(String keyPath, File outputFile, String bucketName) {
        System.out.println("Start downloading file " + keyPath + " to " + outputFile.getPath());

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .build();

        try {
            // Attempt to retrieve the object bytes from S3
            ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getObjectRequest);
            byte[] data = objectBytes.asByteArray();

            // Write the data to a local file
            try (OutputStream os = new FileOutputStream(outputFile)) {
                os.write(data);
                System.out.println("Successfully obtained bytes from an S3 object");
            } catch (IOException e) {
                System.err.println("Error writing to file: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (S3Exception e) {
            System.err.println("Error getting data from s3 " + e.getMessage());
            e.printStackTrace();
        }

    }

    public void createBucketIfNotExists(String bucketName) {
        System.out.println("Creating Bucket " + bucketName + " if does not exist...");
        try {
            // Create the S3 bucket if it does not exist
            s3.createBucket(CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .createBucketConfiguration(
                            CreateBucketConfiguration.builder()
                                    .locationConstraint(BucketLocationConstraint.US_WEST_2)
                                    .build())
                    .build());
    
            // Wait for the bucket to be created
            s3.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
    
            // Define the Bucket Policy to allow EC2 instances in the current AWS account to access objects in the bucket
            String bucketPolicy = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": \"*\",\n" +
                    "      \"Action\": \"s3:GetObject\",\n" +
                    "      \"Resource\": \"arn:aws:s3:::" + bucketName + "/*\",\n" +
                    "      \"Condition\": {\n" +
                    "        \"StringEquals\": {\n" +
                    "          \"aws:PrincipalType\": \"IAMRole\"\n" +
                    "        }\n" +
                    "      }\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";
    
            // Set the bucket policy
            PutBucketPolicyRequest putBucketPolicyRequest = PutBucketPolicyRequest.builder()
                    .bucket(bucketName)
                    .policy(bucketPolicy)
                    .build();
            s3.putBucketPolicy(putBucketPolicyRequest);
    
            System.out.println("Bucket Policy applied successfully.");
    
        } catch (S3Exception e) {
            System.out.println(e.getMessage());
        }
    }
    
    public void createPublicBucketIfNotExists(String bucketName) {
        System.out.println("Creating Bucket " + bucketName + " if does not exist...");
        try {
            // Create the S3 bucket if it does not exist
            s3.createBucket(CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .createBucketConfiguration(
                            CreateBucketConfiguration.builder()
                                    .locationConstraint(BucketLocationConstraint.US_WEST_2)
                                    .build())
                    .build());
    
            // Wait for the bucket to be created
            s3.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
    
            // Define the Bucket Policy to allow EC2 instances in the current AWS account to access objects in the bucket
            String bucketPolicy = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Sid\": \"ListObjectsInBucket\",\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": \"*\",\n" +
                    "      \"Action\": [\"s3:ListBucket\"],\n" +
                    "      \"Resource\": [\"arn:aws:s3:::" + bucketName + "\"]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"Sid\": \"AllObjectActions\",\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": \"*\",\n" +
                    "      \"Action\": \"s3:*Object\",\n" +
                    "      \"Resource\": [\"arn:aws:s3:::" + bucketName + "/*\"]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";
    
            // Set the bucket policy
            PutBucketPolicyRequest putBucketPolicyRequest = PutBucketPolicyRequest.builder()
                    .bucket(bucketName)
                    .policy(bucketPolicy)
                    .build();
            s3.putBucketPolicy(putBucketPolicyRequest);
    
            System.out.println("Bucket Policy applied successfully.");
    
        } catch (S3Exception e) {
            System.out.println(e.getMessage());
        }
    }


    public SdkIterable<S3Object> listObjectsInBucket(String bucketName) {
        // Build the list objects request
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .maxKeys(1)
                .build();

        ListObjectsV2Iterable listRes = null;
        try {
            listRes = s3.listObjectsV2Paginator(listReq);
        } catch (S3Exception ignored) {
        }
        // Process response pages
        listRes.stream()
                .flatMap(r -> r.contents().stream())
                .forEach(content -> System.out.println(" Key: " + content.key() + " size = " + content.size()));

        return listRes.contents();
    }

    public void deleteEmptyBucket(String bucketName) {
        DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket(bucketName).build();
        try {
            s3.deleteBucket(deleteBucketRequest);
        } catch (S3Exception ignored) {
        }
    }

    public void deleteAllObjectsFromBucket(String bucketName) {
        SdkIterable<S3Object> contents = listObjectsInBucket(bucketName);

        Collection<ObjectIdentifier> keys = contents.stream()
                .map(content ->
                        ObjectIdentifier.builder()
                                .key(content.key())
                                .build())
                .toList();

        Delete del = Delete.builder().objects(keys).build();

        DeleteObjectsRequest multiObjectDeleteRequest = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(del)
                .build();

        try {
            s3.deleteObjects(multiObjectDeleteRequest);
        } catch (S3Exception ignored) {
        }
    }

    public void deleteBucket(String bucketName) {
        deleteAllObjectsFromBucket(bucketName);
        deleteEmptyBucket(bucketName);
    }

    public String generatePresignedUrl(String objectKey) {
        try (S3Presigner presigner = S3Presigner.builder()
            .region(region2) // Set region here
            .build()) {
        // Set up the request to get the object from S3
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(Resources.A1_BUCKET)
                .key(objectKey)
                .build();

        // Generate the presigned URL
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(r -> r.getObjectRequest(getObjectRequest)
                .signatureDuration(Duration.ofMinutes(10))); // URL valid for 10 minutes

        // Return the presigned URL as a string
        URL url = presignedRequest.url();
        return url.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "Error generating presigned URL: " + e.getMessage();
        }
    }
    //////////////////////////////////////////////SQS

    /**
     * @param queueName
     * @return queueUrl
     */
public String createQueue(String queueName) {
    String queueUrl = null;

    try {
        // Try to get the existing queue URL first
        GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build();

        GetQueueUrlResponse getQueueUrlResponse = sqs.getQueueUrl(getQueueUrlRequest);
        queueUrl = getQueueUrlResponse.queueUrl();
        System.out.println("Queue already exists, URL: " + queueUrl);
    } catch (QueueDoesNotExistException e) {
        // If the queue does not exist, create it
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();

        CreateQueueResponse createResult = sqs.createQueue(createQueueRequest);
        queueUrl = createResult.queueUrl();
        System.out.println("Created queue '" + queueName + "', queue URL: " + queueUrl);
    }

    return queueUrl;
}

    public void deleteQueue(String queueUrl) {
        DeleteQueueRequest req =
                DeleteQueueRequest.builder()
                        .queueUrl(queueUrl)
                        .build();

        try {
            sqs.deleteQueue(req);
        } catch (SqsException ignored) {
        }
    }

    public String getQueueUrl(String queueName) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build();
        String queueUrl = null;
        try {
            queueUrl = sqs.getQueueUrl(getQueueRequest).queueUrl();
        } catch (SqsException e) {
            System.err.println(e.awsErrorDetails().errorMessage());
        }
        return queueUrl;
    }

    public int getQueueSize(String queueUrl) {
        GetQueueAttributesRequest getQueueAttributesRequest = GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED
                )
                .build();

        GetQueueAttributesResponse queueAttributesResponse = null;
        try {
            queueAttributesResponse = sqs.getQueueAttributes(getQueueAttributesRequest);
        } catch (SqsException ignored) {
        }
        Map<QueueAttributeName, String> attributes = queueAttributesResponse.attributes();

        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)) +
                Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)) +
                Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
    }
    
        // Method to delete the processed message from the SQS queue
    public void deleteMessageFromQueue(String queueUrl, String receiptHandle) {
        DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build();

        sqs.deleteMessage(deleteMessageRequest);
    }

    public void purgeQueue(String queueUrl) {
        PurgeQueueRequest purgeQueueRequest = PurgeQueueRequest.builder()
                .queueUrl(queueUrl)
                .build();

        sqs.purgeQueue(purgeQueueRequest);
    }

    public Message getMessageFromQueue(String queueUrl, int visibilityTimeout) {

        // Create the receive message request
        ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1) 
                .visibilityTimeout(visibilityTimeout)
                .build();

        // Receive messages from the queue
        ReceiveMessageResponse result = sqs.receiveMessage(receiveMessageRequest);

        // Check if there are any messages to process
        if (result.messages().isEmpty()) {
            return null;
        }

        // Process each retrieved message
        return  result.messages().get(0);
    }

        // Method to send a message to the SQS queue with the relevant details
    public void sendSqsMessage(String queueUrl, String message) {
        SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message)
                .build();

                sqs.sendMessage(sendMessageRequest);
    }

    public void releaseMessageToQueue(String queueUrl, String receiptHandle) {
        try {
            // Reset the visibility timeout to 0 seconds, making the message immediately available
            ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .visibilityTimeout(0)
                    .build();
    
            sqs.changeMessageVisibility(request);
            System.out.println("Message visibility reset, released back to queue.");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error releasing message back to the queue.");
        }
    }
    

    ///////////////////////

    public enum Label {
        Manager,
        Worker
    }
}
