// 판정 fixture: 영역 크기 대신 1 의 개수를 센다. WRONG_ANSWER 여야 한다.
import java.util.*;
public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt(), m = in.nextInt(), ones = 0;
        for (int i = 0; i < n * m; i++) ones += in.nextInt();
        System.out.println(ones);
    }
}
