import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.commons.GeneratorAdapter;
import org.jetbrains.org.objectweb.asm.commons.Method;
import org.jetbrains.org.objectweb.asm.commons.MethodRemapper;
import org.jetbrains.org.objectweb.asm.commons.Remapper;
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.FieldNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.InsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;

/** Exact-build experiment, not a general patcher or a supported distribution procedure. */
public final class ProvenancePatch implements Opcodes {
  static final String VF = "com/intellij/openapi/vfs/VirtualFile";
  static final String EVENT = "com/intellij/openapi/vfs/newvfs/events/VFileContentChangeEvent";
  static final String PROJECT = "com/intellij/openapi/project/Project";
  static final String MANAGER = "com/intellij/psi/PsiManager";
  static final String MANAGER_EX = "com/intellij/psi/impl/PsiManagerEx";
  static final String TREE = "com/intellij/psi/impl/PsiTreeChangeEventImpl";
  static final String TRACKER = "com/intellij/psi/impl/PsiModificationTrackerImpl";
  static final String SIMPLE = "com/intellij/openapi/util/SimpleModificationTracker";
  static final String API = "com/intellij/psi/util/PsiModificationTracker";
  static final String LISTENER = "com/intellij/openapi/fileEditor/FileDocumentManagerListener";
  static final String VFS = "com/intellij/psi/impl/file/impl/PsiVFSListener";
  static final String CAUSE = "com/intellij/psi/impl/PsiContentProvenance$Cause";
  static final String CONTENT = "com/intellij/psi/impl/PsiContentProvenance$Content";
  static final String UNKNOWN = "com/intellij/psi/impl/PsiContentProvenance$Unattributed";
  static final String OWNER = "com/intellij/psi/impl/PsiContentProvenance";
  static final String OLD_CALLBACK = "fileWithNoDocumentChanged";
  static final String CONTENT_CALLBACK = "fileContentChangedWithoutDocument";
  static final String COUNT = "getSemanticModificationCount";
  static final Map<String, String> EXPECTED = Map.ofEntries(
      Map.entry(LISTENER, "b57399edb6d92ef596dbddcfa1b7a34a0a7f8edfe0533c7d34f41f0d261d5023"),
      Map.entry(API, "8c2f0e12a3e3ef7d8b2c2b4bcb9b53a771d0d2b28f07eab24d4621da363c07ba"),
      Map.entry(TREE, "93533b669e5d43afa8de38814d3ad3621494240e67d6be5a5568f11edfebc586"),
      Map.entry(TRACKER, "6340eb33f62f463d9222f560c1509ff1130ed43cf01a23de50b2e8ddf2acd26e"),
      Map.entry("com/intellij/openapi/fileEditor/impl/FileDocumentManagerImpl", "182369a6bc75332dda447cef4ef1d5aeba8966168b237e8604c3cd2cb6c34bea"),
      Map.entry("com/intellij/openapi/fileEditor/impl/FileDocumentManagerImpl$FileDocumentManagerListenerBackgroundableBridge", "eb66a28d31498f737dfdc8a7fb79b2da2d08598bf2e770dcb1b0edba14d71d38"),
      Map.entry("com/intellij/psi/impl/file/impl/MyFileDocumentManagerListener", "02db675f58bcc18a5a42b055d49bc2034c927c4fd5fa12cdff9309aca8a4ead7"),
      Map.entry(VFS, "ecf09db7a6bba9a51d383620ddbb62cea69c1d64da46b7eb2157502ef4da3427"));

  static Type type(String name) { return Type.getObjectType(name); }
  static String descriptor(String name) { return "L" + name + ";"; }
  static MethodNode method(ClassNode node, String name, String desc) {
    List<MethodNode> found = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
    if (found.size() != 1) throw new IllegalStateException(node.name + ": expected one " + name + desc);
    return found.getFirst();
  }
  static GeneratorAdapter add(ClassNode node, int access, String name, String desc) {
    if (node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) {
      throw new IllegalStateException("Already patched: " + node.name + "." + name);
    }
    MethodNode result = new MethodNode(access, name, desc, null, null);
    node.methods.add(result);
    GeneratorAdapter g = new GeneratorAdapter(result, access, name, desc);
    g.visitCode();
    return g;
  }
  static void unknown(GeneratorAdapter g) { g.getStatic(type(UNKNOWN), "INSTANCE", type(UNKNOWN)); }

  static void patchListener(ClassNode node) {
    GeneratorAdapter g = add(node, ACC_PUBLIC, CONTENT_CALLBACK, "(" + descriptor(EVENT) + ")V");
    g.loadThis(); g.loadArg(0);
    g.invokeVirtual(type(EVENT), new Method("getFile", "()" + descriptor(VF)));
    g.invokeInterface(type(LISTENER), new Method(OLD_CALLBACK, "(" + descriptor(VF) + ")V"));
    g.returnValue(); g.endMethod();
  }

  static void patchTree(ClassNode node) {
    node.fields.add(new FieldNode(ACC_PRIVATE | ACC_FINAL, "myInvalidationCause", descriptor(CAUSE), null, null));
    MethodNode constructor = method(node, "<init>", "(" + descriptor(MANAGER) + ")V");
    for (AbstractInsnNode i : constructor.instructions.toArray()) {
      if (i.getOpcode() == RETURN) {
        InsnList init = new InsnList();
        init.add(new VarInsnNode(ALOAD, 0));
        init.add(new org.jetbrains.org.objectweb.asm.tree.FieldInsnNode(GETSTATIC, UNKNOWN, "INSTANCE", descriptor(UNKNOWN)));
        init.add(new org.jetbrains.org.objectweb.asm.tree.FieldInsnNode(PUTFIELD, TREE, "myInvalidationCause", descriptor(CAUSE)));
        constructor.instructions.insertBefore(i, init);
      }
    }
    GeneratorAdapter g = add(node, ACC_PUBLIC, "<init>", "(" + descriptor(MANAGER) + descriptor(CONTENT) + ")V");
    g.loadThis(); g.loadArg(0);
    g.invokeConstructor(type("com/intellij/psi/PsiTreeChangeEvent"), new Method("<init>", "(" + descriptor(MANAGER) + ")V"));
    g.loadThis(); g.loadArg(1); g.putField(type(TREE), "myInvalidationCause", type(CAUSE));
    g.returnValue(); g.endMethod();
    g = add(node, ACC_PUBLIC, "getInvalidationCause", "()" + descriptor(CAUSE));
    g.loadThis(); g.getField(type(TREE), "myInvalidationCause", type(CAUSE));
    g.returnValue(); g.endMethod();
  }

  static void patchTracker(ClassNode node) {
    node.fields.add(new FieldNode(ACC_PRIVATE | ACC_FINAL, "mySemanticCount", descriptor(SIMPLE), null, null));
    node.fields.add(new FieldNode(ACC_PRIVATE | ACC_FINAL, "mySemanticProject", descriptor(PROJECT), null, null));
    MethodNode ctor = method(node, "<init>", "(" + descriptor(PROJECT) + ")V");
    for (AbstractInsnNode i : ctor.instructions.toArray()) {
      if (i instanceof MethodInsnNode call && call.name.equals("<init>") && call.owner.equals("java/lang/Object")) {
        MethodNode init = new MethodNode();
        GeneratorAdapter g = new GeneratorAdapter(init, 0, "<init>", "(" + descriptor(PROJECT) + ")V");
        g.loadThis(); g.newInstance(type(SIMPLE)); g.dup(); g.invokeConstructor(type(SIMPLE), new Method("<init>", "()V"));
        g.putField(type(TRACKER), "mySemanticCount", type(SIMPLE));
        g.loadThis(); g.loadArg(0); g.putField(type(TRACKER), "mySemanticProject", type(PROJECT));
        ctor.instructions.insert(i, init.instructions);
        break;
      }
    }
    MethodNode old = method(node, "incCountersInner", "()V");
    old.instructions.clear(); old.tryCatchBlocks.clear(); old.localVariables = null;
    GeneratorAdapter g = new GeneratorAdapter(old, old.access, old.name, old.desc);
    g.loadThis(); unknown(g);
    g.invokeVirtual(type(TRACKER), new Method("incCountersInner", "(" + descriptor(CAUSE) + ")V"));
    g.returnValue(); g.endMethod();
    g = add(node, ACC_PRIVATE, "incCountersInner", "(" + descriptor(CAUSE) + ")V");
    g.loadThis(); g.getField(type(TRACKER), "myModificationCount", type(SIMPLE));
    g.invokeVirtual(type(SIMPLE), new Method("incModificationCount", "()V"));
    g.loadThis(); g.getField(type(TRACKER), "mySemanticProject", type(PROJECT)); g.loadArg(0);
    g.invokeStatic(type(OWNER), new Method("affectsSemanticProject", "(" + descriptor(PROJECT) + descriptor(CAUSE) + ")Z"));
    var publish = g.newLabel(); g.ifZCmp(GeneratorAdapter.EQ, publish);
    g.loadThis(); g.getField(type(TRACKER), "mySemanticCount", type(SIMPLE));
    g.invokeVirtual(type(SIMPLE), new Method("incModificationCount", "()V"));
    g.mark(publish); g.loadThis(); g.invokeVirtual(type(TRACKER), new Method("fireEvent", "()V"));
    g.returnValue(); g.endMethod();
    int replacements = 0;
    for (AbstractInsnNode i : method(node, "treeChanged", "(" + descriptor(TREE) + ")V").instructions.toArray()) {
      if (i instanceof MethodInsnNode call && call.name.equals("incCountersInner")) {
        InsnList cause = new InsnList(); cause.add(new VarInsnNode(ALOAD, 1));
        cause.add(new MethodInsnNode(INVOKEVIRTUAL, TREE, "getInvalidationCause", "()" + descriptor(CAUSE), false));
        method(node, "treeChanged", "(" + descriptor(TREE) + ")V").instructions.insertBefore(i, cause);
        call.desc = "(" + descriptor(CAUSE) + ")V"; replacements++;
      }
    }
    if (replacements != 1) throw new IllegalStateException("Tracker increment route changed");
    g = add(node, ACC_PUBLIC, COUNT, "()J");
    g.loadThis(); g.getField(type(TRACKER), "mySemanticCount", type(SIMPLE));
    g.invokeVirtual(type(SIMPLE), new Method("getModificationCount", "()J"));
    g.returnValue(); g.endMethod();
  }

  static void patchContentPublisher(ClassNode node) {
    MethodNode m = method(node, "contentsChanged", "(" + descriptor(EVENT) + ")V");
    int replacements = 0;
    for (AbstractInsnNode i : m.instructions.toArray()) {
      if (i instanceof MethodInsnNode call && call.name.equals(OLD_CALLBACK)) {
        InsnList retainEvent = new InsnList(); retainEvent.add(new InsnNode(POP)); retainEvent.add(new VarInsnNode(ALOAD, 1));
        m.instructions.insertBefore(i, retainEvent);
        call.name = CONTENT_CALLBACK; call.desc = "(" + descriptor(EVENT) + ")V"; replacements++;
      }
    }
    if (replacements != 1) throw new IllegalStateException("Content publisher route changed");
  }

  static void patchBridge(ClassNode node) {
    Remapper remapper = new Remapper() {
      @Override public String map(String name) { return name.equals(VF) ? EVENT : name; }
      @Override public String mapMethodName(String owner, String name, String desc) {
        if (name.equals(OLD_CALLBACK)) return CONTENT_CALLBACK;
        if (name.equals("lambda$" + OLD_CALLBACK + "$0")) return "lambda$" + CONTENT_CALLBACK + "$0";
        return name;
      }
    };
    for (String name : List.of(OLD_CALLBACK, "lambda$" + OLD_CALLBACK + "$0")) {
      MethodNode original = method(node, name, "(" + descriptor(VF) + ")V");
      MethodNode copy = new MethodNode(original.access, remapper.mapMethodName(node.name, name, original.desc),
          remapper.mapMethodDesc(original.desc), null, null);
      original.accept(new MethodRemapper(copy, remapper));
      node.methods.add(copy);
    }
  }

  static void patchProjectListener(ClassNode node) {
    GeneratorAdapter g = add(node, ACC_PUBLIC, CONTENT_CALLBACK, "(" + descriptor(EVENT) + ")V");
    g.loadThis(); g.getField(type(node.name), "fileManager", type("com/intellij/psi/impl/file/impl/FileManagerEx"));
    g.loadArg(0); g.invokeVirtual(type(EVENT), new Method("getFile", "()" + descriptor(VF)));
    g.invokeInterface(type("com/intellij/psi/impl/file/impl/FileManagerEx"),
        new Method("findCachedViewProviders", "(" + descriptor(VF) + ")Ljava/util/List;"));
    g.invokeInterface(Type.getType(List.class), new Method("isEmpty", "()Z"));
    var loaded = g.newLabel(); g.ifZCmp(GeneratorAdapter.EQ, loaded);
    g.loadThis(); g.getField(type(node.name), "project", type(PROJECT)); g.push(type(VFS));
    g.invokeInterface(type(PROJECT), new Method("getService", "(Ljava/lang/Class;)Ljava/lang/Object;"));
    g.checkCast(type(VFS)); g.loadArg(0);
    g.invokeVirtual(type(VFS), new Method("handleContentChangeWithoutPsi", "(" + descriptor(EVENT) + ")V"));
    g.returnValue();
    g.mark(loaded); g.loadThis(); g.loadArg(0); g.invokeVirtual(type(EVENT), new Method("getFile", "()" + descriptor(VF)));
    g.invokeVirtual(type(node.name), new Method(OLD_CALLBACK, "(" + descriptor(VF) + ")V"));
    g.returnValue(); g.endMethod();
  }

  static void patchVfs(ClassNode node) {
    GeneratorAdapter g = add(node, ACC_PUBLIC | ACC_FINAL, "handleContentChangeWithoutPsi", "(" + descriptor(EVENT) + ")V");
    g.loadThis(); g.loadArg(0); g.invokeVirtual(type(EVENT), new Method("getFile", "()" + descriptor(VF)));
    g.invokeVirtual(type(VFS), new Method("isInRootModel", "(" + descriptor(VF) + ")Z"));
    var end = g.newLabel(); g.ifZCmp(GeneratorAdapter.EQ, end);
    g.loadThis(); g.getField(type(VFS), "manager", type(MANAGER_EX)); g.loadArg(0);
    g.invokeStatic(type(OWNER), new Method("emit", "(" + descriptor(MANAGER_EX) + descriptor(EVENT) + ")V"));
    g.mark(end); g.returnValue(); g.endMethod();
  }

  static byte[] patch(byte[] bytes) {
    ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
    switch (node.name) {
      case LISTENER -> patchListener(node);
      case API -> {
        GeneratorAdapter g = add(node, ACC_PUBLIC, COUNT, "()J");
        g.loadThis(); g.invokeInterface(type(API), new Method("getModificationCount", "()J"));
        g.returnValue(); g.endMethod();
      }
      case TREE -> patchTree(node);
      case TRACKER -> patchTracker(node);
      case "com/intellij/openapi/fileEditor/impl/FileDocumentManagerImpl" -> patchContentPublisher(node);
      case "com/intellij/openapi/fileEditor/impl/FileDocumentManagerImpl$FileDocumentManagerListenerBackgroundableBridge" -> patchBridge(node);
      case "com/intellij/psi/impl/file/impl/MyFileDocumentManagerListener" -> patchProjectListener(node);
      case VFS -> patchVfs(node);
      default -> throw new IllegalArgumentException(node.name);
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    node.accept(writer); return writer.toByteArray();
  }

  static void patchAdapter(Path input, Path output) throws Exception {
    String name = "io/github/amichne/kast/workspace/intellij/read/LiveProjectReadEpochPlatformPort";
    try (ZipFile zip = new ZipFile(input.toFile())) {
      byte[] bytes = zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes();
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!hash.equals("b5c36a0bdc9eb7a530bbfa34e4b2e7d19ae4ac984653beb65a23164683a40d36")) {
        throw new IOException("Unsupported Kast adapter bytes");
      }
      ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
      int replacements = 0;
      for (AbstractInsnNode i : method(node, "psiModificationCount", "()J").instructions.toArray()) {
        if (i instanceof MethodInsnNode call && call.owner.equals(API) && call.name.equals("getModificationCount")) {
          call.name = COUNT; replacements++;
        }
      }
      if (replacements != 1) throw new IOException("Kast PSI observation route changed");
      ClassWriter writer = new ClassWriter(0); node.accept(writer);
      Path target = output.resolve(name + ".class");
      Files.createDirectories(target.getParent()); Files.write(target, writer.toByteArray());
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && args[0].equals("--adapter")) {
      patchAdapter(Path.of(args[1]), Path.of(args[2]));
      return;
    }
    Path home = Path.of(args[0]); Path output = Path.of(args[1]);
    int count = 0;
    for (Path jar : Files.list(home.resolve("lib")).filter(p -> p.toString().endsWith(".jar")).toList()) {
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        for (var entry : EXPECTED.entrySet()) {
          var member = zip.getEntry(entry.getKey() + ".class");
          if (member == null) continue;
          byte[] original = zip.getInputStream(member).readAllBytes();
          String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
          if (!hash.equals(entry.getValue())) throw new IOException("Unsupported class bytes: " + entry.getKey());
          Path target = output.resolve(entry.getKey() + ".class");
          Files.createDirectories(target.getParent()); Files.write(target, patch(original));
          System.out.println(jar.getFileName() + " " + entry.getKey() + " " + hash); count++;
        }
      }
    }
    if (count != EXPECTED.size()) throw new IOException("Incomplete platform patch: " + count);
  }
}
