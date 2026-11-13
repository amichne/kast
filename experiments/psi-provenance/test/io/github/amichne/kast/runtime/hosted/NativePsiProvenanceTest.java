package io.github.amichne.kast.runtime.hosted;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListenerBackgroundable;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.file.impl.FileManagerEx;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.testFramework.IndexingTestUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Native production dispatch and tracker checks; no mocked PSI, index, or counter. */
public final class NativePsiProvenanceTest extends NativeSmartModeFixtureTest {
  @Override protected boolean runInDispatchThread() { return true; }

  private record Inputs(VirtualFile readme, VirtualFile source) {}

  private Inputs inputs() throws Exception {
    VirtualFile base = getOrCreateProjectBaseDir();
    VirtualFile source = WriteAction.compute(() -> base.createChildDirectory(this, "authored"));
    ModuleRootModificationUtil.updateModel(getModule(), model ->
        model.addContentEntry(base).addSourceFolder(source, false));
    Inputs result = WriteAction.compute(() -> {
      VirtualFile readme = base.createChildData(this, "README.md");
      readme.setBinaryContent("documentation\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      VirtualFile kotlin = source.createChildData(this, "Target.kt");
      kotlin.setBinaryContent("class Target\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return new Inputs(readme, kotlin);
    });
    IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    assertNull(FileDocumentManager.getInstance().getCachedDocument(result.readme()));
    assertTrue(PsiManagerEx.getInstanceEx(getProject()).getFileManager()
        .findCachedViewProviders(result.readme()).isEmpty());
    assertTrue(PsiManagerEx.getInstanceEx(getProject()).getFileManager()
        .findCachedViewProviders(result.source()).isEmpty());
    return result;
  }

  private long semanticCount() {
    return PsiModificationTracker.getInstance(getProject()).getSemanticModificationCount();
  }

  private VFileContentChangeEvent content(VirtualFile file) {
    return new VFileContentChangeEvent(this, file, file.getModificationStamp(), file.getModificationStamp() + 1, true);
  }

  public void testReadmeContentRetainsSemanticCountBeforeObserversRun() throws Exception {
    Inputs files = inputs();
    long original = PsiModificationTracker.getInstance(getProject()).getModificationCount();
    long semantic = semanticCount();
    List<Long> observations = new ArrayList<>();
    getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(
        PsiModificationTracker.TOPIC, () -> observations.add(semanticCount()));
    WriteAction.run(() -> RefreshQueue.getInstance().processEvents(false, List.of(content(files.readme()))));
    assertTrue(PsiModificationTracker.getInstance(getProject()).getModificationCount() > original);
    assertEquals(semantic, semanticCount());
    assertFalse(observations.isEmpty());
    for (long observed : observations) assertEquals(semantic, observed);
  }

  public void testMixedContentPreservesEveryContributionInBothOrders() throws Exception {
    Inputs files = inputs();
    AtomicInteger completeBatches = new AtomicInteger();
    ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
        .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
          @Override public void after(List<? extends VFileEvent> events) {
            if (events.stream().anyMatch(e -> e.getFile() == files.readme()) &&
                events.stream().anyMatch(e -> e.getFile() == files.source())) completeBatches.incrementAndGet();
          }
        });
    for (List<VirtualFile> order : List.of(List.of(files.readme(), files.source()), List.of(files.source(), files.readme()))) {
      long before = semanticCount();
      WriteAction.run(() -> RefreshQueue.getInstance().processEvents(false,
          order.stream().map(this::content).toList()));
      assertTrue("source contribution disappeared", semanticCount() > before);
    }
    assertEquals(2, completeBatches.get());
  }

  public void testSourceOwnershipOverridesMarkdownExtensionBeforePublication() throws Exception {
    Inputs files = inputs();
    VirtualFile sourceReadme = WriteAction.compute(() -> files.source().getParent().createChildData(this, "README.md"));
    long before = semanticCount();
    List<Long> observations = new ArrayList<>();
    getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(
        PsiModificationTracker.TOPIC, () -> observations.add(semanticCount()));
    WriteAction.run(() -> RefreshQueue.getInstance().processEvents(false, List.of(content(sourceReadme))));
    assertTrue(semanticCount() > before);
    assertFalse(observations.isEmpty());
    for (long observed : observations) assertTrue(observed > before);
  }

  public void testKotlinExtensionOutsideSourceRootsDoesNotInventSourceOwnership() throws Exception {
    inputs();
    VirtualFile unowned = WriteAction.compute(() -> getOrCreateProjectBaseDir().createChildData(this, "Unowned.kt"));
    long before = semanticCount();
    WriteAction.run(() -> RefreshQueue.getInstance().processEvents(false, List.of(content(unowned))));
    assertEquals(before, semanticCount());
  }

  public void testLibrarySourceAndClassRootsRemainRelevantWithoutCachedPsi() throws Exception {
    inputs();
    var directory = java.nio.file.Files.createTempDirectory("psi-provenance-library-");
    disposeOnTearDown(() -> assertTrue(FileUtil.delete(directory.toFile())));
    VirtualFile dependency = getVirtualFile(directory.toFile());
    List<VirtualFile> roots = WriteAction.compute(() -> List.of(
        dependency.createChildDirectory(this, "classes"), dependency.createChildDirectory(this, "sources")));
    List<VirtualFile> files = WriteAction.compute(() -> {
      List<VirtualFile> result = new ArrayList<>();
      for (VirtualFile root : roots) {
        VirtualFile file = root.createChildData(this, "metadata.md");
        file.setBinaryContent("library metadata\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.add(file);
      }
      return result;
    });
    ModuleRootModificationUtil.addModuleLibrary(getModule(), "provenance-dependency",
        List.of(roots.get(0).getUrl()), List.of(roots.get(1).getUrl()));
    IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    ProjectFileIndex index = ProjectFileIndex.getInstance(getProject());
    assertTrue(index.isInLibraryClasses(files.get(0)));
    assertTrue(index.isInLibrarySource(files.get(1)));
    List<Long> observations = new ArrayList<>();
    getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(
        PsiModificationTracker.TOPIC, () -> observations.add(semanticCount()));
    for (VirtualFile file : files) {
      assertTrue(index.isInLibrary(file));
      assertFalse(index.isInContent(file));
      assertFalse(index.isInSourceContent(file));
      assertNull(FileDocumentManager.getInstance().getCachedDocument(file));
      assertTrue(PsiManagerEx.getInstanceEx(getProject()).getFileManager().findCachedViewProviders(file).isEmpty());
      observations.clear();
      long before = semanticCount();
      WriteAction.run(() -> file.setBinaryContent("changed library metadata\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      assertTrue("library contribution was discounted", semanticCount() > before);
      assertFalse(observations.isEmpty());
      for (long observed : observations) assertTrue(observed > before);
    }
  }

  public void testForcedReloadOfUnownedReadmeRemainsGlobal() throws Exception {
    Inputs files = inputs();
    var manager = assertInstanceOf(PsiManagerEx.getInstanceEx(getProject()).getFileManager(), FileManagerEx.class);
    var psi = PsiManager.getInstance(getProject()).findFile(files.readme());
    assertNotNull(psi);
    assertFalse(manager.findCachedViewProviders(files.readme()).isEmpty());
    var parent = manager.getCachedDirectory(files.readme().getParent());
    assertNotNull(parent);
    AtomicInteger filelessChanges = new AtomicInteger();
    PsiManager.getInstance(getProject()).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
      @Override public void childrenChanged(PsiTreeChangeEvent event) {
        if (event.getFile() == null && event.getParent() == parent) {
          filelessChanges.incrementAndGet();
        }
      }
    }, getTestRootDisposable());
    long before = semanticCount();
    WriteAction.run(() -> manager.forceReload(files.readme()));
    assertTrue("forced reload was discounted", semanticCount() > before);
    assertTrue(filelessChanges.get() > 0);
    assertTrue(manager.findCachedViewProviders(files.readme()).isEmpty());
  }

  public void testForcedReloadNestedInReadmeDeliveryRemainsGlobal() throws Exception {
    Inputs files = inputs();
    VirtualFile target = WriteAction.compute(() -> getOrCreateProjectBaseDir().createChildData(this, "Other.md"));
    var manager = assertInstanceOf(PsiManagerEx.getInstanceEx(getProject()).getFileManager(), FileManagerEx.class);
    var psi = PsiManager.getInstance(getProject()).findFile(target);
    assertNotNull(psi);
    assertFalse(manager.findCachedViewProviders(target).isEmpty());
    List<Long> reloadCounts = new ArrayList<>();
    ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
        .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
          @Override public void after(List<? extends VFileEvent> events) {
            if (events.stream().anyMatch(e -> e instanceof VFileContentChangeEvent && e.getFile() == files.readme())) {
              reloadCounts.add(semanticCount());
              manager.forceReload(target);
              reloadCounts.add(semanticCount());
            }
          }
        });
    long before = semanticCount();
    WriteAction.run(() -> files.readme().setBinaryContent("changed documentation\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(2, reloadCounts.size());
    assertEquals(before, reloadCounts.get(0).longValue());
    assertTrue("nested forced reload was discounted", reloadCounts.get(1) > reloadCounts.get(0));
    assertTrue(manager.findCachedViewProviders(target).isEmpty());
  }

  public void testNestedCacheDropDuringReadmeDeliveryRemainsGlobal() throws Exception {
    Inputs files = inputs();
    AtomicInteger nestedDrops = new AtomicInteger();
    ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
        .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
          @Override public void after(List<? extends VFileEvent> events) {
            if (events.stream().anyMatch(e -> e instanceof VFileContentChangeEvent && e.getFile() == files.readme())) {
              nestedDrops.incrementAndGet();
              PsiManager.getInstance(getProject()).dropPsiCaches();
            }
          }
        });
    long before = semanticCount();
    WriteAction.run(() -> RefreshQueue.getInstance().processEvents(false, List.of(content(files.readme()))));
    assertEquals(1, nestedDrops.get());
    assertTrue("nested global invalidation was discounted", semanticCount() > before);
  }

  public void testStandaloneCacheDropRemainsGlobal() throws Exception {
    inputs();
    long before = semanticCount();
    WriteAction.run(() -> PsiManager.getInstance(getProject()).dropPsiCaches());
    assertTrue(semanticCount() > before);
  }

  public void testLegacyNoDocumentCallbackRemainsUnattributed() throws Exception {
    Inputs files = inputs();
    long before = semanticCount();
    WriteAction.run(() -> ApplicationManager.getApplication().getMessageBus()
        .syncPublisher(FileDocumentManagerListenerBackgroundable.TOPIC).fileWithNoDocumentChanged(files.readme()));
    assertTrue("legacy callback acquired invented content provenance", semanticCount() > before);
  }

  public void testLoadedSourceDocumentEditRemainsRelevant() throws Exception {
    Inputs files = inputs();
    var document = FileDocumentManager.getInstance().getDocument(files.source());
    assertNotNull(document);
    PsiManager.getInstance(getProject()).findFile(files.source());
    long before = semanticCount();
    WriteAction.run(() -> {
      document.setText("class ChangedTarget\n");
      PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    });
    assertTrue(semanticCount() > before);
  }
}
