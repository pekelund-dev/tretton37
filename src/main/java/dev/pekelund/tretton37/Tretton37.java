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

public class Tretton37 {
    private static final String WEBSITE_URL = "https://books.toscrape.com/";
    private static final String FILE_PATH = "./files/";
    private static Queue<String> pagesToVisit = new ConcurrentLinkedQueue<>();
    private static final Set<String> visitedPages = ConcurrentHashMap.newKeySet();

    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public static void main(String[] args) throws IOException, InterruptedException {
        visitPage(WEBSITE_URL);
        do {
            String url = pagesToVisit.poll();
            executor.submit(() -> {
                try {
                    visitPage(url);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            // if the pagesToVisit queue is empty, then wait a second to allow for
            // late threads to add more pages to it.
            if (pagesToVisit.isEmpty()) {
                executor.awaitTermination(1000, TimeUnit.MILLISECONDS);
            }
        } while (!pagesToVisit.isEmpty());
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    private static void visitPage(String url) throws IOException {
        if (!visitedPages.add(url)) {
            return;
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
}