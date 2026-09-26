// 판정 fixture: 격자에서 1 로 이루어진 가장 큰 영역(BFS). ACCEPTED 여야 한다.
#include <bits/stdc++.h>
using namespace std;

int main() {
    int n, m;
    if (!(cin >> n >> m)) return 0;
    vector<vector<int>> g(n, vector<int>(m));
    for (auto& row : g)
        for (auto& v : row) cin >> v;
    vector<vector<bool>> seen(n, vector<bool>(m, false));
    int best = 0;
    const int dx[4] = {-1, 1, 0, 0}, dy[4] = {0, 0, -1, 1};
    for (int i = 0; i < n; i++)
        for (int j = 0; j < m; j++) {
            if (g[i][j] != 1 || seen[i][j]) continue;
            seen[i][j] = true;
            deque<pair<int, int>> q{{i, j}};
            int size = 0;
            while (!q.empty()) {
                auto [x, y] = q.front();
                q.pop_front();
                size++;
                for (int k = 0; k < 4; k++) {
                    int nx = x + dx[k], ny = y + dy[k];
                    if (0 <= nx && nx < n && 0 <= ny && ny < m && !seen[nx][ny] && g[nx][ny] == 1) {
                        seen[nx][ny] = true;
                        q.push_back({nx, ny});
                    }
                }
            }
            best = max(best, size);
        }
    cout << best << "\n";
}
