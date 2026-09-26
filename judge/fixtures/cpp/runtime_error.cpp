// 판정 fixture: 범위 밖 접근을 at() 으로 해서 예외로 끝난다. RUNTIME_ERROR 여야 한다.
#include <bits/stdc++.h>
int main() {
    std::vector<int> v(3);
    std::cout << v.at(10) << "\n";
}
