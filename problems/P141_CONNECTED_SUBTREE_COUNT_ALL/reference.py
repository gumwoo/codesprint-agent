import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    MOD = 10**9 + 7
    adj = [[] for _ in range(n + 1)]
    for i in range(n - 1):
        u = int(data[1 + 2 * i]); v = int(data[2 + 2 * i])
        adj[u].append(v); adj[v].append(u)
    parent = [0] * (n + 1)
    parent[1] = -1
    order = []
    stack = [1]
    while stack:
        v = stack.pop()
        order.append(v)
        for w in adj[v]:
            if w != parent[v]:
                parent[w] = v
                stack.append(w)
    down = [1] * (n + 1)
    for v in reversed(order):
        p = parent[v]
        if p > 0:
            down[p] = down[p] * (down[v] + 1) % MOD
    up = [0] * (n + 1)
    for v in order:
        ch = [w for w in adj[v] if w != parent[v]]
        k = len(ch)
        if k == 0:
            continue
        pre = [1] * (k + 1)
        for i in range(k):
            pre[i + 1] = pre[i] * (down[ch[i]] + 1) % MOD
        suf = 1
        base = up[v] + 1
        for i in range(k - 1, -1, -1):
            up[ch[i]] = base * pre[i] % MOD * suf % MOD
            suf = suf * (down[ch[i]] + 1) % MOD
    out = [str(down[v] * (up[v] + 1) % MOD) for v in range(1, n + 1)]
    sys.stdout.write('\n'.join(out) + '\n')

main()
