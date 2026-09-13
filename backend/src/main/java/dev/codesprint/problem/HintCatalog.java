package dev.codesprint.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 단계별 힌트를 읽는다. 정본은 {@code problems/<CODE>/hints.yaml} 이다(ADR-0026).
 *
 * <p><b>H6(전체 풀이)은 이 파일에 없다.</b> {@code reference.py} 가 그것이고, 복사본을
 * 만들지 않는다(ADR-0012) - 두 곳에 적으면 한쪽만 고쳐진 날 사용자가 틀린 풀이를 본다.
 * 그래서 6단계를 요청하면 여기서 그 파일을 그대로 읽어 준다.
 *
 * <p>{@link ProblemCatalog} 와 마찬가지로 <b>검증하지 않는다.</b> 사다리가 1..5 로
 * 온전한지, 정답 코드를 담고 있지 않은지는 {@code tools/check_problems.py} 가 CI 에서
 * 본다. 같은 검사를 두 언어로 두면 둘이 갈라진다.
 */
@Component
public class HintCatalog {

    /** 사다리의 마지막 칸. {@code hints.yaml} 이 아니라 {@code reference.py} 에서 온다. */
    public static final int SOLUTION_LEVEL = 6;

    private final Path root;
    private final Map<String, List<String>> ladders;

    public HintCatalog(@Value("${codesprint.problems-dir}") String problemsDir) {
        this.root = Path.of(problemsDir);
        this.ladders = load(this.root);
    }

    /** 그 문제의 H1~H5. 문제를 모르면 빈 목록이다. */
    public List<String> ladderOf(String problemCode) {
        return ladders.getOrDefault(problemCode, List.of());
    }

    /** 사다리에서 가장 높은 단계. H6 을 포함한다. */
    public int topLevel(String problemCode) {
        List<String> ladder = ladderOf(problemCode);
        return ladder.isEmpty() ? 0 : SOLUTION_LEVEL;
    }

    /**
     * 한 단계의 내용. 1..5 는 사다리에서, 6 은 {@code reference.py} 에서 온다.
     *
     * @return 그 단계가 없으면 null
     */
    public String textOf(String problemCode, int level) {
        if (level == SOLUTION_LEVEL) {
            return solutionOf(problemCode);
        }
        List<String> ladder = ladderOf(problemCode);
        if (level < 1 || level > ladder.size()) {
            return null;
        }
        return ladder.get(level - 1);
    }

    /**
     * 전체 풀이. <b>미리 읽어 두지 않는다</b> - 정답을 필요할 때만 메모리에 올린다.
     * 사다리와 달리 이것은 요청이 실제로 6단계까지 갔을 때만 쓰인다.
     */
    private String solutionOf(String problemCode) {
        Path file = root.resolve(problemCode).resolve("reference.py");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("정답 풀이를 읽지 못했다: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("문제 디렉터리가 없다: " + root.toAbsolutePath());
        }
        Map<String, List<String>> loaded = new LinkedHashMap<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve("hints.yaml");
                if (!Files.exists(file)) {
                    continue;
                }
                Map<String, Object> doc;
                try (var in = Files.newInputStream(file)) {
                    doc = new Yaml().load(in);
                }
                List<String> texts = new ArrayList<>();
                for (Map<String, Object> row :
                        (List<Map<String, Object>>) doc.getOrDefault("hints", List.of())) {
                    texts.add((String) row.get("text"));
                }
                loaded.put(dir.getFileName().toString(), List.copyOf(texts));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("힌트를 읽지 못했다: " + root, e);
        }
        if (loaded.isEmpty()) {
            // 조용히 비어 있으면 모든 힌트 요청이 404 가 되고, 아무도 그 이유를 모른다.
            throw new IllegalStateException("힌트를 하나도 읽지 못했다: " + root.toAbsolutePath());
        }
        return Map.copyOf(loaded);
    }
}
