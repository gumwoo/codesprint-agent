# 멈춤 모임을 판정할 때 같은 모임 안으로 가는 관계도 모임 밖으로 나가는 관계로 셌다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = [[] for _ in range(n)]
    for i in range(m):
        u = int(data[2 + 2 * i]) - 1
        v = int(data[3 + 2 * i]) - 1
        adj[u].append(v)
    index = [-1] * n
    low = [0] * n
    onst = [False] * n
    comp = [-1] * n
    it = [0] * n
    st = []
    cnt = 0
    c = 0
    for s in range(n):
        if index[s] != -1:
            continue
        index[s] = low[s] = cnt
        cnt += 1
        st.append(s)
        onst[s] = True
        call = [s]
        while call:
            v = call[-1]
            if it[v] < len(adj[v]):
                w = adj[v][it[v]]
                it[v] += 1
                if index[w] == -1:
                    index[w] = low[w] = cnt
                    cnt += 1
                    st.append(w)
                    onst[w] = True
                    call.append(w)
                elif onst[w]:
                    if index[w] < low[v]:
                        low[v] = index[w]
            else:
                call.pop()
                if call:
                    p = call[-1]
                    if low[v] < low[p]:
                        low[p] = low[v]
                if low[v] == index[v]:
                    while True:
                        w = st.pop()
                        onst[w] = False
                        comp[w] = c
                        if w == v:
                            break
                    c += 1
    out = [False] * c
    for u in range(n):
        cu = comp[u]
        for v in adj[u]:
            out[cu] = True
    sinks = sum(1 for x in out if not x)
    print(c)
    print(sinks)


main()
