// 판정 fixture: 메모리를 계속 붙잡는다. MEMORY_LIMIT 여야 한다.
#include <bits/stdc++.h>
int main() {
    std::vector<std::vector<char>> keep;
    while (true) {
        keep.emplace_back(16 * 1024 * 1024, 1);  // 실제로 써서 페이지를 잡는다
    }
}
