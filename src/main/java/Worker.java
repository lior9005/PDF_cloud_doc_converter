import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.imageio.ImageIO;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;



public class Worker {

    AWS aws = AWS.getInstance();
    SqsClient sqsClient = SqsClient.create();
    S3Client s3Client = S3Client.create();


    public static void main(String[] args) {
        Worker worker = new Worker();
        // Get the queue URL from the command line arguments
        worker.run(args[--]);     
    }

    public void run(String incomingQueueUrl, String outgoingQueueUrl, String terminateQueueUrl, String s3BucketName) {
        while (!shouldTerminate(terminateQueueUrl)) {
            Message msg = aws.getMessageFromQueue(incomingQueueUrl, 5);
            processMsg(msg.body(), s3BucketName, outgoingQueueUrl,aws);
            aws.deleteMessageFromQueue(incomingQueueUrl, msg.receiptHandle());
        }
    }

    public void processMsg(String inputMessage, String s3BucketName, String outgoingQueueUrl, AWS aws) {
        try {
            String[] parts = inputMessage.split("\t");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid input format. Expected 'operation<tab>pdfUrl'.");
            }
            String origin = parts[0].trim();
            String operation = parts[1].trim();
            String pdfUrl = parts[2].trim();
    
            // Download the PDF file
            File pdfFile = downloadPdf(pdfUrl);
    
            // Generate an S3 object key
            String outputKey = pdfFile.getName().split("\\.")[1] + "_" + operation.toLowerCase() + ".result";
    
            // Perform the requested operation and directly upload to S3
            String message = performOperation(origin ,pdfFile, operation, s3BucketName, aws, outputKey);

            aws.sendSqsMessage(outgoingQueueUrl, message);
    
        } catch (Exception e) {
            e.printStackTrace(); // Log the error
        }
    }
    

    public boolean shouldTerminate(String terminateQueueUrl) {
        Message msg = aws.getMessageFromQueue(terminateQueueUrl, 1);
        return (msg.body().equals("terminate"))? true : false;
    }


    
// Method to download the PDF from the given URL
    private static File downloadPdf(String pdfUrl) throws IOException {
        URL url = new URL(pdfUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoInput(true);
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Page not found: " + responseCode);
        }

        InputStream inputStream = connection.getInputStream();
        File pdfFile = new File("downloads", pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1)); // Save in 'downloads' folder
        try (OutputStream outputStream = new FileOutputStream(pdfFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return pdfFile;
    }

    public String performOperation(String origin, File pdfFile, String operation, String s3BucketName, AWS aws, String outputKey) {

        // Initialize the result message to handle errors
        String resultMessage = "";
    
        // Create a temporary file to hold the processed data
        File tempFile = null;
    
        try {
            // Create a temporary file for output
            tempFile = File.createTempFile("temp", "." + operation.toLowerCase());
    
            // Perform the requested operation
            if ("ToImage".equalsIgnoreCase(operation)) {
                try {
                    convertToImage(pdfFile, tempFile);  // Try converting to image
                } catch (Exception e) {
                    throw new IOException("Error during ToImage conversion: " + e.getMessage());
                }
            } else if ("ToHTML".equalsIgnoreCase(operation)) {
                try {
                    convertToHtml(pdfFile, tempFile);  // Try converting to HTML
                } catch (Exception e) {
                    throw new IOException("Error during ToHTML conversion: " + e.getMessage());
                }
            } else if ("ToText".equalsIgnoreCase(operation)) {
                try {
                    convertToText(pdfFile, tempFile);  // Try converting to text
                } catch (Exception e) {
                    throw new IOException("Error during ToText conversion: " + e.getMessage());
                }
            } else {
                throw new IllegalArgumentException("Unknown operation: " + operation);
            }
    
            // Attempt to upload the result to S3 (fail silently if there's an error)
            try {
                aws.uploadFileToS3(outputKey, tempFile);
            } catch (Exception e) {}
    
        } catch (IOException | IllegalArgumentException e) {
            // Handle exceptions from conversion operations and return a message with the exception description
            resultMessage = String.format(
                "<p>%s %s: <a href=\"%s\">%s</a> %s</p>",
                origin,  // Add the origin at the beginning
                operation, 
                pdfFile.toURI().toString(),  // Link to the input PDF file
                pdfFile.getName(),
                e.getMessage()  // Short description of the exception
            );
        } finally {
            // Clean up by deleting the temporary file if it exists
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    
        // If no exception occurred, return the successful operation message
        if (resultMessage.isEmpty()) {
            // Generate the S3 URL for the output file
            String pdfUrl = "s3://" + s3BucketName + "/" + outputKey;  // S3 URL
            String outputFileName = outputKey.split("/")[outputKey.split("/").length - 1]; // Extract the output file name
    
            // Format the success message
            resultMessage = String.format(
                "<p>%s: <a href=\"%s\">%s</a> <a href=\"%s\">%s</a></p>",
                operation, 
                pdfFile.toURI().toString(),  // Link to the input PDF file
                pdfFile.getName(),
                pdfUrl, // Link to the output file in S3
                outputFileName  // Name of the output file in S3
            );
        }
    
        return origin + "\t" + resultMessage;
    }
    
    
    

    // Method to convert PDF to image
    public static void convertToImage(File pdfFile, File outputFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(0); // Render the first page
    
            // Write directly to the output file
            ImageIO.write(image, "PNG", outputFile);
        }
    }

    // Method to convert PDF to HTML
    public static void convertToHtml(File pdfFile, File outputFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
    
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write("<html><body>\n");
                writer.write("<pre>" + text + "</pre>\n");
                writer.write("</body></html>");
            }
        }
    }
    
    // Method to convert PDF to text
    public static void convertToText(File pdfFile, File outputFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
    
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write(text);
            }
        }
    }     
}
