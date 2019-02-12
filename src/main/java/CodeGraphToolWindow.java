import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import guru.nidi.graphviz.attribute.RankDir;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.MutableNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

import static guru.nidi.graphviz.model.Factory.mutGraph;
import static guru.nidi.graphviz.model.Factory.mutNode;

@SuppressWarnings("WeakerAccess")
public class CodeGraphToolWindow {
    private JButton runButton;
    private JPanel codeGraphToolWindowContent;
    private JPanel canvasPanel;

    public CodeGraphToolWindow() {
        runButton.addActionListener(e -> run());
    }

    public void run() {
        Project project = getActiveProject();
        if (project == null) {
            return;
        }
        System.out.println("--- getting source code files ---");
        Set<PsiFile> sourceCodeFiles = getSourceCodeFiles(project);
        System.out.println(String.format("found %d files", sourceCodeFiles.size()));
        System.out.println("--- getting method references ---");
        Map<PsiMethod, Set<PsiMethod>> methodCallersMap = getMethodCallersMap(sourceCodeFiles);
        System.out.println(String.format("found %d methods and %d callers in total", methodCallersMap.size(),
                methodCallersMap.values().stream().map(Set::size).mapToInt(Integer::intValue).sum()));
        System.out.println("--- building graph ---");
        Graph graph = buildGraph(methodCallersMap);
        System.out.println("--- getting layout from GraphViz ---");
        layoutByGraphViz(graph);
        System.out.println("--- rendering graph ---");
        JPanel viewPanel = renderGraphOnCanvas(graph);
        System.out.println("--- rendered ---");
        attachEventListeners(viewPanel);
        System.out.println("--- attached event listeners ---");
        graph.getNodes().forEach(node -> System.out.printf("node [%s] %s (%.2f, %.2f)\n",
                node.getId(), node.getMethod().getName(), node.getX(), node.getY()));
        graph.getEdges().forEach(edge -> System.out.printf("edge [%s] %s -> %s\n",
                edge.getId(), edge.getSourceNode().getMethod().getName(), edge.getTargetNode().getMethod().getName()));
    }

    @Nullable
    private Project getActiveProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        Optional<Project> maybeActiveProject = Arrays.stream(projects)
                .filter(project -> {
                    Window window = WindowManager.getInstance().suggestParentWindow(project);
                    return window != null && window.isActive();
                })
                .findFirst();
        return maybeActiveProject.orElse(null);
    }

    @NotNull
    private Set<PsiFile> getSourceCodeFiles(Project project) {
        VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
        System.out.println(String.format("found %d source roots", sourceRoots.length));
        return Arrays.stream(sourceRoots)
                .flatMap(contentSourceRoot -> {
                    List<VirtualFile> childrenVirtualFiles = new ArrayList<>();
                    ContentIterator contentIterator = child -> {
                        if (child.isValid() && !child.isDirectory()) {
                            String extension = Optional.ofNullable(child.getExtension()).orElse("");
                            if (extension.equals("java")) {
                                childrenVirtualFiles.add(child);
                            }
                        }
                        return true;
                    };
                    VfsUtilCore.iterateChildrenRecursively(contentSourceRoot, null, contentIterator);
                    return childrenVirtualFiles.stream()
                            .map(file -> PsiManager.getInstance(project).findFile(file));
                })
                .collect(Collectors.toSet());
    }

    @NotNull
    private Map<PsiMethod, Set<PsiMethod>> getMethodCallersMap(Set<PsiFile> sourceCodeFiles) {
        Set<PsiMethod> allMethods = sourceCodeFiles.stream()
                .flatMap(psiFile -> Arrays.stream(((PsiJavaFile)psiFile).getClasses())) // get all classes
                .flatMap(psiClass -> Arrays.stream(psiClass.getMethods())) // get all methods
                .collect(Collectors.toSet());
        return allMethods.stream()
                .collect(Collectors.toMap(
                        method -> method,
                        method -> {
                            Collection<PsiReference> references = ReferencesSearch.search(method).findAll();
                            return references.stream()
                                    .map(reference -> getContainingKnownMethod(reference.getElement(), allMethods))
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet());
                        }
                ));
    }

    private Graph buildGraph(@NotNull Map<PsiMethod, Set<PsiMethod>> methodCallersMap) {
        Graph graph = new Graph();
        methodCallersMap.forEach((callee, callers) -> {
            graph.addNode(callee);
            callers.forEach(caller -> {
                graph.addNode(caller);
                graph.addEdge(caller, callee);
            });
        });
        return graph;
    }

    @NotNull
    private JPanel renderGraphOnCanvas(@NotNull Graph graph) {
        // TODO pass graph in and render edges on canvas
        JPanel viewPanel = new Canvas();
        canvasPanel.removeAll();
        canvasPanel.add(viewPanel);
        canvasPanel.updateUI();
        return viewPanel;
    }

    private void attachEventListeners(@NotNull JPanel viewPanel) {
        // TODO
//        viewPanel.setMouseManager(new MouseEventHandler());
    }

    @Nullable
    private PsiMethod getContainingKnownMethod(@NotNull PsiElement psiElement, @NotNull Set<PsiMethod> knownMethods) {
        PsiMethod parent = PsiTreeUtil.getParentOfType(psiElement, PsiMethod.class);
        if (parent == null) {
            return null;
        }
        return knownMethods.contains(parent) ? parent : getContainingKnownMethod(parent, knownMethods);
    }

    private void layoutByGraphViz(@NotNull Graph graph) {
        guru.nidi.graphviz.model.MutableGraph gvGraph = mutGraph("test")
                .setDirected(true)
                .graphAttrs()
                .add(RankDir.LEFT_TO_RIGHT);

        TreeSet<Node> sortedNodeSet = createSortedNodeSet(graph.getNodes());
        sortedNodeSet.forEach(node -> {
            MutableNode gvNode = mutNode(node.getId());
            Collection<Node> neighbors = node.getLeavingEdges()
                    .values()
                    .stream()
                    .map(Edge::getTargetNode)
                    .collect(Collectors.toSet());
            TreeSet<Node> sortedNeighbors = createSortedNodeSet(neighbors);
            sortedNeighbors.forEach(neighborNode -> gvNode.addLink(neighborNode.getId()));
            gvGraph.add(gvNode);
        });
        String layoutBlueprint = Graphviz.fromGraph(gvGraph).render(Format.PLAIN).toString();

        // parse the GraphViz layout as a mapping from "node name" to "x-y coordinate (percent of full graph size)"
        // GraphViz doc: https://graphviz.gitlab.io/_pages/doc/info/output.html#d:plain
        List<String> layoutLines = Arrays.asList(layoutBlueprint.split("\n"));
        String graphSizeLine = layoutLines.stream()
                .filter(line -> line.startsWith("graph"))
                .findFirst()
                .orElse("");
        String[] graphSizeParts = graphSizeLine.split(" ");
        float graphWidth = Float.parseFloat(graphSizeParts[2]);
        float graphHeight = Float.parseFloat(graphSizeParts[3]);
        layoutLines.stream()
                .filter(line -> line.startsWith("node"))
                .map(line -> line.split(" "))
                .forEach(parts -> {
                    String nodeId = parts[1];
                    float x = Float.parseFloat(parts[2]) / graphWidth;
                    float y = Float.parseFloat(parts[3]) / graphHeight;
                    graph.getNode(nodeId).setCoordinate(x, y);
                });
    }

    @NotNull
    private TreeSet<Node> createSortedNodeSet(@NotNull Collection<Node> nodes) {
        Comparator<Node> comparator = Comparator.comparing(node -> node.getMethod().getName());
        return nodes.stream().collect(Collectors.toCollection(() -> new TreeSet<>(comparator)));
    }

    @NotNull
    public JPanel getContent() {
        return codeGraphToolWindowContent;
    }
}
