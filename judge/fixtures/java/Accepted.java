// 판정 fixture: 격자에서 1 로 이루어진 가장 큰 영역(BFS). ACCEPTED 여야 한다.
// 채점기는 이 파일을 Main.java 로 마운트한다 - public class 이름이 Main 이어야 한다.
import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws IOException {
        StreamTokenizer in = new StreamTokenizer(new BufferedReader(new InputStreamReader(System.in)));
        in.nextToken();
        int n = (int) in.nval;
        in.nextToken();
        int m = (int) in.nval;
        int[][] g = new int[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                in.nextToken();
                g[i][j] = (int) in.nval;
            }
        boolean[][] seen = new boolean[n][m];
        int[] dx = {-1, 1, 0, 0}, dy = {0, 0, -1, 1};
        int best = 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                if (g[i][j] != 1 || seen[i][j]) continue;
                seen[i][j] = true;
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[] {i, j});
                int size = 0;
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    size++;
                    for (int k = 0; k < 4; k++) {
                        int nx = c[0] + dx[k], ny = c[1] + dy[k];
                        if (0 <= nx && nx < n && 0 <= ny && ny < m && !seen[nx][ny] && g[nx][ny] == 1) {
                            seen[nx][ny] = true;
                            q.add(new int[] {nx, ny});
                        }
                    }
                }
                best = Math.max(best, size);
            }
        System.out.println(best);
    }
}
