/*
 Copyright 2013-2015 Jason Leyba

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.github.jsdossier;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.write;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import com.google.common.jimfs.Jimfs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

@RunWith(Parameterized.class)
public class EndToEndTest {

  private static Path tmpDir;
  private static Path srcDir;

  private static void initFileSystem() throws IOException {
    FileSystem fileSystem;
    if (Boolean.getBoolean("dossier.e2e.useDefaultFileSystem")) {
      fileSystem = FileSystems.getDefault();

      String customTmpDir = System.getProperty("dossier.e2e.useDir");
      if (!isNullOrEmpty(customTmpDir)) {
        tmpDir = fileSystem.getPath(customTmpDir);
        createDirectories(tmpDir);
      } else {
        tmpDir = fileSystem.getPath(System.getProperty("java.io.tmpdir"));
        tmpDir = createTempDirectory(tmpDir, "dossier.e2e");
      }
    } else {
      fileSystem = Jimfs.newFileSystem();
      tmpDir = fileSystem.getPath("/tmp");
      createDirectories(tmpDir);
    }

    srcDir = tmpDir.resolve("src");

    copyResource("resources/SimpleReadme.md", srcDir.resolve("SimpleReadme.md"));
    copyResource("resources/Custom.md", srcDir.resolve("Custom.md"));
    copyResource("resources/closure_module.js", srcDir.resolve("main/closure_module.js"));
    copyResource("resources/filter.js", srcDir.resolve("main/filter.js"));
    copyResource("resources/globals.js", srcDir.resolve("main/globals.js"));
    copyResource("resources/json.js", srcDir.resolve("main/json.js"));
    copyResource("resources/inheritance.js", srcDir.resolve("main/inheritance.js"));
    copyResource("resources/emptyenum.js", srcDir.resolve("main/subdir/emptyenum.js"));
    copyResource("resources/module/index.js", srcDir.resolve("main/example/index.js"));
    copyResource("resources/module/nested.js", srcDir.resolve("main/example/nested.js"));
    copyResource("resources/module/worker.js", srcDir.resolve("main/example/worker.js"));
  }

  private static Supplier<Path> createDataSupplier(final Path output) {
    return Suppliers.memoize(new Supplier<Path>() {
      @Override
      public Path get() {
        try {
          return generateData(output);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  private static Path generateData(final Path output) throws IOException {
    System.out.println("Generating output in " + output);
    Path config = createTempFile(tmpDir, "config", ".json");
    writeConfig(config, new Config() {{
      setOutput(output);
      setReadme(srcDir.resolve("SimpleReadme.md"));

      addCustomPage("Custom Page", srcDir.resolve("Custom.md"));

      addFilter("^foo.\\w+_.*");
      addFilter("foo.FilteredClass");
      addFilter("foo.bar");

      addSource(srcDir.resolve("main/closure_module.js"));
      addSource(srcDir.resolve("main/filter.js"));
      addSource(srcDir.resolve("main/globals.js"));
      addSource(srcDir.resolve("main/json.js"));
      addSource(srcDir.resolve("main/inheritance.js"));
      addSource(srcDir.resolve("main/subdir/emptyenum.js"));

      addModule(srcDir.resolve("main/example/index.js"));
      addModule(srcDir.resolve("main/example/nested.js"));
      addModule(srcDir.resolve("main/example/worker.js"));
    }});

    Main.run(new String[]{"-c", config.toAbsolutePath().toString()}, srcDir.getFileSystem());

    if (output.toString().endsWith(".zip")) {
      FileSystem fs;
      if (output.getFileSystem() == FileSystems.getDefault()) {
        URI uri = URI.create("jar:file:" + output.toAbsolutePath());
        fs = FileSystems.newFileSystem(uri, ImmutableMap.<String, Object>of());
      } else {
        fs = output.getFileSystem()
            .provider()
            .newFileSystem(output, ImmutableMap.<String, Object>of());
      }
      return fs.getPath("/");
    }
    return output;
  }

  @Parameters(name = "{1}")
  public static Collection<Object[]> data() throws IOException {
    initFileSystem();
    ImmutableList.Builder<Object[]> data = ImmutableList.builder();

    data.add(new Object[]{createDataSupplier(tmpDir.resolveSibling("out")), "directory"});
    data.add(new Object[]{createDataSupplier(tmpDir.resolveSibling("out.zip")), "zip file"});

    return data.build();
  }

  private final Supplier<Path> outputDirSupplier;
  private final String scenario;

  private Path outDir;

  public EndToEndTest(Supplier<Path> outputDirSupplier, String scenario) {
    this.outputDirSupplier = outputDirSupplier;
    this.scenario = scenario;
  }

  @Override
  public String toString() {
    return "EndToEndTest::" + scenario;
  }

  @Before
  public void initOutputDir() {
    outDir = outputDirSupplier.get();
  }

  @Test
  public void checkCopiesAllSourceFiles() {
    assertExists(outDir.resolve("source/closure_module.js.src.html"));
    assertExists(outDir.resolve("source/globals.js.src.html"));
    assertExists(outDir.resolve("source/json.js.src.html"));
    assertExists(outDir.resolve("source/example/index.js.src.html"));
    assertExists(outDir.resolve("source/subdir/emptyenum.js.src.html"));
  }

  @Test
  public void checkSourceFileRendering() throws IOException {
    Document document = load(outDir.resolve("source/subdir/emptyenum.js.src.html"));
    compareWithGoldenFile(
        querySelector(document, "article.srcfile"),
        "source/subdir/emptyenum.js.src.html");
    checkHeader(document);
    compareWithGoldenFile(querySelectorAll(document, "nav"), "source/subdir/nav.html");
    compareWithGoldenFile(querySelectorAll(document, "main ~ *"), "source/subdir/footer.html");
  }

  @Test
  public void checkMarkdownIndexProcessing() throws IOException {
    Document document = load(outDir.resolve("index.html"));
    compareWithGoldenFile(querySelector(document, "article.indexfile"), "index.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkGlobalClass() throws IOException {
    Document document = load(outDir.resolve("GlobalCtor.html"));
    compareWithGoldenFile(querySelector(document, "article"), "GlobalCtor.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkGlobalEnum() throws IOException {
    Document document = load(outDir.resolve("GlobalEnum.html"));
    compareWithGoldenFile(querySelector(document, "article"), "GlobalEnum.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkEmptyGlobalEnum() throws IOException {
    Document document = load(outDir.resolve("EmptyEnum.html"));
    compareWithGoldenFile(querySelector(document, "article"), "EmptyEnum.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkGlobalUndefinedEnum() throws IOException {
    Document document = load(outDir.resolve("UndefinedEnum.html"));
    compareWithGoldenFile(querySelector(document, "article"), "UndefinedEnum.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkDeprecatedClass() throws IOException {
    Document document = load(outDir.resolve("DeprecatedFoo.html"));
    compareWithGoldenFile(querySelector(document, "article"), "DeprecatedFoo.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkFunctionNamespace() throws IOException {
    Document document = load(outDir.resolve("sample_json.html"));
    compareWithGoldenFile(querySelector(document, "article"), "sample_json.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkInterfaceThatExtendsOtherInterfaces() throws IOException {
    Document document = load(outDir.resolve("sample_inheritance_LeafInterface.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "sample_inheritance_LeafInterface.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkExportedApiOfClosureModule() throws IOException {
    Document document = load(outDir.resolve("closure_module.html"));
    compareWithGoldenFile(querySelector(document, "article"), "closure_module.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkClassDefiendOnClosureModuleExportsObject() throws IOException {
    Document document = load(outDir.resolve("closure_module_Clazz.html"));
    compareWithGoldenFile(querySelector(document, "article"), "closure_module_Clazz.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkClassExportedByClosureModule() throws IOException {
    Document document = load(outDir.resolve("closure_module_PubClass.html"));
    compareWithGoldenFile(querySelector(document, "article"), "closure_module_PubClass.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkClassExtendsTemplateClass() throws IOException {
    Document document = load(outDir.resolve("sample_inheritance_NumberClass.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "sample_inheritance_NumberClass.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkGoogDefinedClass() throws IOException {
    Document document = load(outDir.resolve("sample_inheritance_StringClass.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "sample_inheritance_StringClass.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkPackageIndexCommonJsModule() throws IOException {
    Document document = load(outDir.resolve("module_example.html"));
    compareWithGoldenFile(querySelector(document, "article"), "module_example.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkCommonJsModule() throws IOException {
    Document document = load(outDir.resolve("module_example_nested.html"));
    compareWithGoldenFile(querySelector(document, "article"), "module_example_nested.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkCommonJsModuleThatIsExportedConstructor() throws IOException {
    Document document = load(outDir.resolve("module_example_worker.html"));
    compareWithGoldenFile(querySelector(document, "article"), "module_example_worker.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkCommonJsModuleClassAlias() throws IOException {
    Document document = load(outDir.resolve("module_example_Greeter.html"));
    compareWithGoldenFile(querySelector(document, "article"), "module_example_Greeter.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkCommonJsModuleExportedInterface() throws IOException {
    Document document = load(outDir.resolve("module_example_nested_IdGenerator.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "module_example_nested_IdGenerator.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkCommonJsModuleInterfaceImplementation() throws IOException {
    Document document = load(outDir.resolve(
        "module_example_nested_IncrementingIdGenerator.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "module_example_nested_IncrementingIdGenerator.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkClassThatExtendsExternType() throws IOException {
    Document document = load(outDir.resolve(
        "sample_inheritance_RunnableError.html"));
    compareWithGoldenFile(querySelector(document, "article"),
        "sample_inheritance_RunnableError.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkNamespaceWithFilteredTypes() throws IOException {
    Document document = load(outDir.resolve("foo.html"));
    compareWithGoldenFile(querySelector(document, "article"), "foo.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  @Test
  public void checkUnfilteredAliasOfFilteredClass() throws IOException {
    Document document = load(outDir.resolve("foo_quot_OneBarAlias.html"));
    compareWithGoldenFile(querySelector(document, "article"), "foo_quot_OneBarAlias.html");
    checkHeader(document);
    checkNav(document);
    checkFooter(document);
  }

  private void checkHeader(Document document) throws IOException {
    compareWithGoldenFile(querySelector(document, "header"), "header.html");
  }

  private void checkFooter(Document document) throws IOException {
    compareWithGoldenFile(querySelectorAll(document, "main ~ *"), "footer.html");
  }

  private void checkNav(Document document) throws IOException {
    compareWithGoldenFile(querySelectorAll(document, "nav"), "nav.html");
  }

  private void compareWithGoldenFile(Element element, String goldenPath) throws IOException {
    compareWithGoldenFile(element.toString(), goldenPath);
  }

  private void compareWithGoldenFile(Elements elements, String goldenPath) throws IOException {
    compareWithGoldenFile(elements.toString(), goldenPath);
  }

  private void compareWithGoldenFile(String actual, String goldenPath) throws IOException {
    goldenPath = "resources/golden/" + goldenPath;
    String golden = Resources.toString(EndToEndTest.class.getResource(goldenPath), UTF_8);
    assertEquals(golden.trim(), actual.trim());
  }

  private static Document load(Path path) throws IOException {
    String html = toString(path);
    Document document = Jsoup.parse(html);
    document.outputSettings()
        .prettyPrint(true)
        .indentAmount(2);
    return document;
  }

  private static Element querySelector(Document document, String selector) {
    Elements elements = document.select(selector);
    checkState(!elements.isEmpty(),
        "Selector %s not found in %s", selector, document);
    return Iterables.getOnlyElement(elements);
  }

  private static Elements querySelectorAll(Document document, String selector) {
    return document.select(selector);
  }

  private static String toString(Path path) throws IOException {
    return new String(readAllBytes(path), UTF_8);
  }

  private static void writeConfig(Path path, Config config) throws IOException {
    write(path, ImmutableList.of(config.toString()), UTF_8);
  }

  private static void copyResource(String from, Path to) throws IOException {
    createDirectories(to.getParent());

    InputStream resource = EndToEndTest.class.getResourceAsStream(from);
    checkNotNull(resource, "Resource not found: %s", from);
    copy(resource, to, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void assertExists(Path path) {
    assertWithMessage("Expected to exist: " + path).that(Files.exists(path)).isTrue();
  }

  private static class Config {
    private final JsonObject json = new JsonObject();
    private final JsonArray customPages = new JsonArray();
    private final JsonArray typeFilters = new JsonArray();
    private final JsonArray sources = new JsonArray();
    private final JsonArray modules = new JsonArray();

    void addCustomPage(String name, Path path) {
      JsonObject spec = new JsonObject();
      spec.addProperty("name", name);
      spec.addProperty("path", path.toString());
      customPages.add(spec);
      json.add("customPages", customPages);
    }

    void addFilter(String name) {
      typeFilters.add(new JsonPrimitive(name));
      json.add("typeFilters", typeFilters);
    }

    void setOutput(Path path) {
      json.addProperty("output", path.toString());
    }

    void setReadme(Path path) {
      json.addProperty("readme", path.toString());
    }

    void addSource(Path path) {
      sources.add(new JsonPrimitive(path.toString()));
      json.add("sources", sources);
    }

    void addModule(Path path) {
      modules.add(new JsonPrimitive(path.toString()));
      json.add("modules", modules);
    }

    @Override
    public String toString() {
      return json.toString();
    }
  }
}
