// 판정 fixture: 영역 크기 대신 1 의 개수를 센다. WRONG_ANSWER 여야 한다.
#include <bits/stdc++.h>
int main() {
    int n, m, v, ones = 0;
    std::cin >> n >> m;
    for (int i = 0; i < n * m; i++) { std::cin >> v; ones += v; }
    std::cout << ones << "\n";
}
