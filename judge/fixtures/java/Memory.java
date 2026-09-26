// 판정 fixture: 힙을 계속 붙잡는다. OutOfMemoryError 로 MEMORY_LIMIT 여야 한다.
import java.util.*;
public class Main {
    public static void main(String[] args) {
        List<long[]> keep = new ArrayList<>();
        while (true) keep.add(new long[1 << 20]);
    }
}
