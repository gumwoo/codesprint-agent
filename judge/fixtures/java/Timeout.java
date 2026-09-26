// 판정 fixture: 끝나지 않는다. TIME_LIMIT 여야 한다.
public class Main {
    static volatile long x = 0;
    public static void main(String[] args) {
        while (true) x++;
    }
}
