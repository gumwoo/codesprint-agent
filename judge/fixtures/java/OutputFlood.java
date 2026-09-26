// 판정 fixture: 출력을 끝없이 쏟는다. OUTPUT_LIMIT 여야 한다.
import java.io.*;
public class Main {
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));
        String line = "x".repeat(1000);
        while (true) { out.println(line); if (out.checkError()) System.exit(1); }
    }
}
