import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import javax.imageio.ImageIO;
import software.amazon.awssdk.services.sqs.model.Message;


public class Worker {

    private AWS aws = AWS.getInstance();

    public static void main(String[] args) {
        Worker worker = new Worker();
        worker.run();
    }

    public void run() {
        while (!shouldTerminate()) {
            Message msg = aws.getMessageFromQueue(Resources.MANAGER_TO_WORKER_QUEUE, 5);
            if(msg == null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            else{
                processMsg(msg.body());
                aws.deleteMessageFromQueue(Resources.MANAGER_TO_WORKER_QUEUE, msg.receiptHandle());
            }
        }
    }

    public void processMsg(String inputMessage) {
        try {
            // parts = [origin, operation, pdfUrl]
            String[] parts = inputMessage.split("\t");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid input format. Expected 'operation<tab>pdfUrl'.");
            }
    
            // Download the PDF file
            File tempPdfFile = downloadPdf(parts[2]);
    
            // Generate an S3 object key
            String outputKey = tempPdfFile.getName().split("\\.")[1] + "_" + parts[1].toLowerCase() + ".result";
    
            // Perform the requested operation and directly upload to S3
            String outputPdfURL = performOperation(tempPdfFile, parts[1], outputKey);

            aws.sendSqsMessage(Resources.WORKER_TO_MANAGER_QUEUE, inputMessage + '\t' + outputPdfURL);
    
        } catch (Exception e) {
            e.printStackTrace(); // Log the error
        }
    }
    

    public boolean shouldTerminate() {
        Message msg = aws.getMessageFromQueue(Resources.TERMINATE_QUEUE, 1);
        if(msg != null) {
            aws.deleteMessageFromQueue(Resources.TERMINATE_QUEUE, msg.receiptHandle());
            return true;
        }
        return false;
    }
    
// Method to download the PDF from the given URL
    private File downloadPdf(String pdfUrl) throws IOException {
        URL url;
        try {
            url = new URI(pdfUrl).toURL();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL syntax: " + pdfUrl, e);
        }
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

    public String performOperation(File pdfFile, String operation, String outputKey) throws Exception {
        File tempFile = null;
        String newPath = "";
        try {
            //temporary file for output
            tempFile = File.createTempFile("temp", "." + operation.toLowerCase());
    
            // Perform the requested operation
            if ("ToImage".equalsIgnoreCase(operation)) {
                try {
                    convertToImage(pdfFile, tempFile);
                } catch (Exception e) {
                    return "Error during ToImage conversion: " + e.getMessage();
                }
            } else if ("ToHTML".equalsIgnoreCase(operation)) {
                try {
                    convertToHtml(pdfFile, tempFile);
                } catch (Exception e) {
                    return "Error during ToHTML conversion: " + e.getMessage();
                }
            } else if ("ToText".equalsIgnoreCase(operation)) {
                try {
                    convertToText(pdfFile, tempFile);
                } catch (Exception e) {
                    return "Error during ToText conversion: " + e.getMessage();
                }
            } else {
                return "Unknown operation: " + operation;
            }
    
            //upload the result to S3
            newPath = aws.uploadFileToS3(outputKey, tempFile, Resources.OUTPUT_BUCKET);

        } catch (IOException | IllegalArgumentException e) {
            return e.getMessage(); 
        } finally {
            // Clean up by deleting the temporary file if it exists
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        return newPath;
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