// 판정 fixture: 배열 범위 밖 접근. RUNTIME_ERROR 여야 한다.
public class Main {
    public static void main(String[] args) {
        int[] v = new int[3];
        System.out.println(v[10]);
    }
}
