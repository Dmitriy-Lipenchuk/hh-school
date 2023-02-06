package ru.hh.school.homework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import static java.util.Collections.reverseOrder;
import static java.util.Map.Entry.comparingByValue;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

public class Launcher {
  private static final HashMap<String, Long> searchCache = new HashMap<>();

  public static void main(String[] args) throws IOException {
    // Написать код, который, как можно более параллельно:
    // - по заданному пути найдет все "*.java" файлы
    // - для каждого файла вычислит 10 самых популярных слов (см. #naiveCount())
    // - соберет top 10 для каждой папки в которой есть хотя-бы один java файл
    // - для каждого слова сходит в гугл и вернет количество результатов по нему (см. #naiveSearch())
    // - распечатает в консоль результаты в виде:
    // <папка1> - <слово #1> - <кол-во результатов в гугле>
    // <папка1> - <слово #2> - <кол-во результатов в гугле>
    // ...
    // <папка1> - <слово #10> - <кол-во результатов в гугле>
    // <папка2> - <слово #1> - <кол-во результатов в гугле>
    // <папка2> - <слово #2> - <кол-во результатов в гугле>
    // ...
    // <папка2> - <слово #10> - <кол-во результатов в гугле>
    // ...
    //
    // Порядок результатов в консоли не обязательный.
    // При желании naiveSearch и naiveCount можно оптимизировать.

    testCount();
  }

  private static void testCount() throws IOException {
    Path path = Path.of(System.getProperty("user.dir"), "\\parallelism\\src\\main\\java\\ru\\hh\\school\\parallelism\\");
    ExecutorService fileExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    ExecutorService searchExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    List<Path> directories = Files.walk(path)
            .filter(Files::isDirectory)
            .toList();

    for (Path directory : directories) {
      CompletableFuture.supplyAsync(() -> getTopTenWordsInDirectory(directory), fileExecutor)
              .thenAccept(map -> {
                Set<String> words = map.keySet();
                for (String word : words) {
                  CompletableFuture.supplyAsync(() -> naiveSearch(word), searchExecutor)
                          .thenAccept(searchResult ->
                                  System.out.printf("%-100s | %-20s | %-10s\n", directory, word, searchResult));
                }
              });
    }

    shutdownAndAwaitTermination(fileExecutor);
    shutdownAndAwaitTermination(searchExecutor);
  }

  private static Map<String, Long> getTopTenWordsInDirectory(Path directoryPath) {
    final Map<String, Long> wordsFrequency = new HashMap<>();

    try {
      Files.list(directoryPath)
              .filter(Files::isRegularFile)
              .forEach(file -> fillMap(file, wordsFrequency));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return wordsFrequency.entrySet()
            .stream()
            .sorted(comparingByValue(reverseOrder()))
            .limit(10)
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static void fillMap(Path filePath, Map<String, Long> wordsFrequency) {
    try {
      Files.lines(filePath)
              .flatMap(line -> Stream.of(line.split("[^a-zA-Z0-9]")))
              .filter(word -> word.length() > 3)
              .collect(groupingBy(identity(), counting()))
              .entrySet()
              .stream()
              .sorted(comparingByValue(reverseOrder()))
              .limit(10)
              .forEach(x -> wordsFrequency.merge(x.getKey(), x.getValue(), Long::sum));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static long naiveSearch(String query) {
    if (searchCache.containsKey(query)) {
      return searchCache.get(query);
    }

    Document document = null;
    try {
      document = Jsoup //
              .connect("https://www.google.com/search?q=" + query) //
              .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.116 Safari/537.36") //
              .get();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Element divResultStats = document.select("div#result-stats").first(); // при slim_appbar крашится при query = "annotations"
    String text = divResultStats.text();
    String resultsPart = text.substring(0, text.indexOf('('));

    long result = Long.parseLong(resultsPart.replaceAll("[^0-9]", ""));

    searchCache.put(query, result);

    return result;
  }

  private static void shutdownAndAwaitTermination(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException ie) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
