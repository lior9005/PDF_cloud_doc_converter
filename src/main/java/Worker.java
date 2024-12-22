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
    private File downloadFile = null;

    public static void main(String[] args) {
        Worker worker = new Worker();
        worker.run();
    }

    public void run() {
        while (!shouldTerminate()) {
            Message msg = aws.getMessageFromQueue(Resources.MANAGER_TO_WORKER_QUEUE, 60);
            if(msg == null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            else{
                System.out.println(msg.body());
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
                throw new IllegalArgumentException("Invalid input format. Expected 'origin<tab>operation<tab>pdfUrl'.");
            }
    
            // Download the PDF file
            String details = downloadPdf(parts[2]);
    
            // Generate an S3 object key
            if(details != ""){
                aws.sendSqsMessage(Resources.WORKER_TO_MANAGER_QUEUE, inputMessage + '\t' + details);
            }
            else{
                String outputKey = downloadFile.getName() + "_" + parts[1].toLowerCase() + ".result";
    
                // Perform the requested operation and directly upload to S3
                String outputPdfURL = performOperation(downloadFile, parts[1], outputKey);
                aws.sendSqsMessage(Resources.WORKER_TO_MANAGER_QUEUE, inputMessage + '\t' + outputPdfURL);
            }

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
    private String downloadPdf(String pdfUrl) throws IOException {
        URL url;
        try {
            url = new URI(pdfUrl).toURL();
        } catch (URISyntaxException e) {
            return "Invalid URL syntax";
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoInput(true);
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            System.out.println(pdfUrl);
            return "Page not found";
        }

        InputStream inputStream = connection.getInputStream();
        File pdfFile = new File(pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1));
        try (OutputStream outputStream = new FileOutputStream(pdfFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        downloadFile = pdfFile;
        return "";
    }

    public String performOperation(File pdfFile, String operation, String outputKey) throws Exception {
        String convertedFilePath = null;
        String newPath = "";
        File toUpload = null;
        try {
            //temporary file for output
            convertedFilePath = pdfFile + "_" + operation.toLowerCase();
    
            // Perform the requested operation
            if ("ToImage".equalsIgnoreCase(operation)) {
                try {
                    convertToImage(pdfFile, convertedFilePath + ".png");
                    toUpload = new File(convertedFilePath + ".png");
                } catch (Exception e) {
                    return "Error during ToImage conversion: " + e.getMessage();
                }
            } else if ("ToHTML".equalsIgnoreCase(operation)) {
                try {
                    convertToHtml(pdfFile, convertedFilePath + ".html");
                    toUpload = new File(convertedFilePath + ".html");
                } catch (Exception e) {
                    return "Error during ToHTML conversion: " + e.getMessage();
                }
            } else if ("ToText".equalsIgnoreCase(operation)) {
                try {
                    convertToText(pdfFile, convertedFilePath + ".txt");
                    toUpload = new File(convertedFilePath + ".txt");
                } catch (Exception e) {
                    return "Error during ToText conversion: " + e.getMessage();
                }
            } else {
                return "Unknown operation: " + operation;
            }
            
            //upload the result to S3
            newPath = aws.uploadFileToS3(outputKey, toUpload , Resources.OUTPUT_BUCKET);

        } catch (IOException | IllegalArgumentException e) {
            return e.getMessage(); 
        } finally {
            // Clean up by deleting the file if it exists
          //  if (new File(convertedFilePath) != null && new File(convertedFilePath).exists()) {
         //       new File(convertedFilePath).delete();
           // }
        }
        return newPath;
    }

    // Method to convert PDF to image
    public static void convertToImage(File pdfFile, String outputFilePath) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(0); // Render the first page
    
            // Write directly to the output file
            ImageIO.write(image, "PNG", new File(outputFilePath));
        }
    }

    // Method to convert PDF to HTML
    public static void convertToHtml(File pdfFile, String outputFilePath) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
    
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                writer.write("<html><body>\n");
                writer.write("<pre>" + text + "</pre>\n");
                writer.write("</body></html>");
            }
        }
    }
    
    // Method to convert PDF to text
    public static void convertToText(File pdfFile,String outputFilePath) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
    
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                writer.write(text);
            }
        }
    }     
}