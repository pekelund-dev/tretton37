package dev.pekelund.tretton37;

import org.apache.http.ParseException;
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
    private static Queue<String> pagesToVisit = new ConcurrentLinkedQueue<>();
    private static final Set<String> visitedPages = ConcurrentHashMap.newKeySet();

    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // AtomicInteger to track the number of pages visited in a thread-safe manner
    private static final AtomicInteger pagesVisited = new AtomicInteger(0);
    private static final int updateFrequency = 10; // How often to update the progress.
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
                    numberOfThreads.decrementAndGet();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally{
                    phaser.arriveAndDeregister();
                }
            });

            // if the pagesToVisit queue is empty, then wait a second to allow for
            // late threads to add more pages to it.
            if (pagesToVisit.isEmpty()) {
                phaser.arriveAndAwaitAdvance();
            }
        } while (!pagesToVisit.isEmpty());
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        System.out.println("\nFinished");
    }

    private static void visitPage(String url) throws IOException {
        if (!visitedPages.add(url)) {
            return;
        }

        int count = pagesVisited.incrementAndGet();
        if (count % updateFrequency == 0) {
            System.out.printf("\rPages visited: %d | Pages left to visit: %d | Threads: %d%50s", count, pagesToVisit.size(), numberOfThreads.get(), "");
        }
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {

                String contentType = response.getEntity().getContentType().getValue();
                if (contentType != null && (contentType.equals("image/x-icon") || contentType.equals("image/jpeg"))) {
                    saveToBinaryFile(url, response.getEntity().getContent());
                } else {
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

    private static void saveToBinaryFile(String url, InputStream inputStream) throws IOException {
        String fileName = url.replace(WEBSITE_URL, "");

        File file = new File(FILE_PATH + fileName);
        file.getParentFile().mkdirs();
        Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}