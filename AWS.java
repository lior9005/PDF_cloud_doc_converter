package API;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AWS {

    public final String IMAGE_AMI = "ami-08902199a8aa0bc09"; // Default AMI ID
    public Region region1 = Region.US_WEST_2; // AWS region
    private final S3Client s3; // S3 client instance
    private final SqsClient sqs; // SQS client instance
    private final Ec2Client ec2; // EC2 client instance
    private final String bucketName; // Default bucket name
    private static AWS instance = null; // Singleton instance

    // Private constructor to initialize AWS clients and default bucket name
    private AWS() {
        s3 = S3Client.builder().region(region1).build();
        sqs = SqsClient.builder().region(region1).build();
        ec2 = Ec2Client.builder().region(region1).build();
        bucketName = "my-bucket";
    }

    // Singleton pattern to ensure only one instance of AWS class
    public static AWS getInstance() {
        if (instance == null) {
            return new AWS();
        }
        return instance;
    }

    ///////////////////////////// EC2 Methods /////////////////////////////

    // Launches EC2 instances from a specified AMI
    public void runInstanceFromAMI(String ami) {
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(ami)
                .instanceType(InstanceType.T2_MICRO) // Specify instance type
                .minCount(1) // Minimum number of instances
                .maxCount(5) // Maximum number of instances
                .build();

        try {
            ec2.runInstances(runInstancesRequest); // Make the API call to launch instances
        } catch (Exception ignored) {
        }
    }

    // Launches EC2 instances with a user-specified script
    public RunInstancesResponse runInstanceFromAmiWithScript(String ami, InstanceType instanceType, int min, int max, String script) {
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(ami)
                .instanceType(instanceType)
                .minCount(min)
                .maxCount(max)
                .userData(Base64.getEncoder().encodeToString(script.getBytes())) // Pass user data (script)
                .build();

        try {
            return ec2.runInstances(runInstancesRequest); // Launch instances and return response
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Retrieves a list of all EC2 instances
    public List<Instance> getAllInstances() {
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder().build();

        try {
            DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(describeInstancesRequest);
            // Flatten the reservations and return instances
            return describeInstancesResponse.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .toList();
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    // Retrieves instances filtered by a specific label (tag)
    public List<Instance> getAllInstancesWithLabel(Label label) {
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("tag:Label").values(label.toString()).build())
                .build();

        try {
            DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(describeInstancesRequest);
            return describeInstancesResponse.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Terminates an EC2 instance by its instance ID
    public void terminateInstance(String instanceId) {
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

        try {
            ec2.terminateInstances(terminateRequest); // Call AWS API to terminate the instance
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("Terminated instance: " + instanceId);
    }

    ///////////////////////////// S3 Methods /////////////////////////////

    // Uploads a file to an S3 bucket
    public String uploadFileToS3(String keyPath, File file) {
        System.out.printf("Start upload: %s, to S3\n", file.getName());

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .build();

        try {
            s3.putObject(req, file.toPath());
            return "s3://" + bucketName + "/" + keyPath; // Return S3 file path
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Downloads a file from an S3 bucket
    public void downloadFileFromS3(String keyPath, File outputFile) {
        System.out.println("Start downloading file " + keyPath + " to " + outputFile.getPath());

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .build();

        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getObjectRequest);
            byte[] data = objectBytes.asByteArray();
            try (OutputStream os = new FileOutputStream(outputFile)) {
                os.write(data);
            }
        } catch (Exception ignored) {
        }
    }

    // Additional S3 methods...

    ///////////////////////////// SQS Methods /////////////////////////////

    // Creates a new SQS queue
    public String createQueue(String queueName) {
        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();
        try {
            CreateQueueResponse createResult = sqs.createQueue(request);
            return createResult.queueUrl(); // Return queue URL
        } catch (Exception ignored) {
        }
        return null;
    }

    // Additional SQS methods...

    ///////////////////////////// Supporting Classes /////////////////////////////

    public enum Label {
        Manager, Worker
    }
}
