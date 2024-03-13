package dev.pekelund.tretton37;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Tretton37 {
    private static final String WEBSITE_URL = "https://books.toscrape.com/";
    private static final String FILE_PATH = "./files/";

    // Queue to track the pages to visit, and a Set to track the pages already visited
    private static Queue<String> pagesToVisit = new ConcurrentLinkedQueue<>();
    private static final Set<String> visitedPages = ConcurrentHashMap.newKeySet();

    // ExecutorService to manage the threads
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // How often to update the progress.
    private static final int updateFrequency = 10; 
    // Phaser for advanced thread synchronization, ensuring coordinated progress updates
    private final static Phaser phaser = new Phaser(1); // Start with 1 to prevent the phaser from terminating immediately
    // AtomicInteger to track the number of active threads
    private static final AtomicInteger numberOfThreads = new AtomicInteger(0);
    
    public static void main(String[] args) throws IOException, InterruptedException {
        visitPage(WEBSITE_URL);
        do {
            String url = pagesToVisit.poll();
            phaser.register();
            executor.submit(() -> {
                try {
                    numberOfThreads.incrementAndGet();
                    visitPage(url);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally{
                    numberOfThreads.decrementAndGet();
                    phaser.arriveAndDeregister();
                }
            });

            // if the pagesToVisit queue is empty, then wait for all threads to finish and then allow
            // late threads to add more pages to it.
            if (pagesToVisit.isEmpty()) {
                phaser.arriveAndAwaitAdvance();
            }
        } while (!pagesToVisit.isEmpty());
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        System.out.printf("\rPages visited: %4d | Pages left to visit: %4d | Threads: %4d%50s", visitedPages.size(), pagesToVisit.size(), numberOfThreads.get(), "");
        System.out.println("\nFinished");
    }

    /**
     * Visit a web page and download the content, parse the links, and saving to file.
     * Found links are added to the pagesToVisit queue only if they have not already been visited.
     *
     * @param  url  the URL of the web page to be visited
     * @throws IOException  if an I/O error occurs while visiting the web page
     */
    private static void visitPage(String url) throws IOException {
        if (!visitedPages.add(url)) {
            return;
        }

        int count = visitedPages.size();
        if (count % updateFrequency == 0) {
            System.out.printf("\rPages visited: %4d | Pages left to visit: %4d | Threads: %4d%50s", count, pagesToVisit.size(), numberOfThreads.get(), "");
        }
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {

                String contentType = response.getEntity().getContentType().getValue();
                // Only save image files,  
                if (contentType != null && (contentType.equals("image/x-icon") || contentType.equals("image/jpeg"))) {
                    saveToBinaryFile(url, response.getEntity().getContent());
                } else {
                    // Parse other files and extract links to other pages, add them to
                    // the pagesToVisit queue and save the file to disk.
                    String content = EntityUtils.toString(response.getEntity());
                    saveToFile(url, content);

                    String baseUrl = url.substring(0, url.lastIndexOf("/") + 1);
                    Document doc = Jsoup.parse(content, baseUrl);
                    Elements textLinks = doc.select("a[href], link[href], img[src], script[src]");

                    for (Element link : textLinks) {
                        String absUrl = link.absUrl("href");
                        if (absUrl.isEmpty()) {
                            absUrl = link.absUrl("src");
                        }
                        if (absUrl.contains(WEBSITE_URL)) {
                            pagesToVisit.add(absUrl);
                        }
                    }
                }
            }
        }
    }

    /**
     * Saves content to a file at the specified URL.
     *
     * @param  url      the URL of the file
     * @param  content  the content to be saved
     * @throws IOException  if an I/O error occurs
     */
    private static void saveToFile(String url, String content) throws IOException {
        String fileName = url.replace(WEBSITE_URL, "");
        if (fileName.isEmpty()) {
            fileName = "index.html";
        }

        File file = new File(FILE_PATH + fileName);
        file.getParentFile().mkdirs();
        try (PrintWriter out = new PrintWriter(file)) {
            out.println(content);
        }
    }

    /**
     * Save the contents from an InputStream to a binary file at a specified URL.
     *
     * @param  url          the URL of the file to be saved
     * @param  inputStream  the InputStream containing the data to be saved
     * @throws IOException  if an I/O error occurs
     */
    private static void saveToBinaryFile(String url, InputStream inputStream) throws IOException {
        String fileName = url.replace(WEBSITE_URL, "");

        File file = new File(FILE_PATH + fileName);
        file.getParentFile().mkdirs();
        Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}