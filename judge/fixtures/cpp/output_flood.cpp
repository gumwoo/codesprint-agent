// 판정 fixture: 출력을 끝없이 쏟는다. OUTPUT_LIMIT 여야 한다.
#include <bits/stdc++.h>
int main() {
    std::string line(1000, 'x');
    while (true) std::cout << line << "\n";
}
