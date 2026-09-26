import sys

MOD = 1_000_000_007


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    nums = list(map(int, data[1:1 + 2 * q]))
    m = max(nums)
    fact = [1] * (m + 1)
    for i in range(1, m + 1):
        fact[i] = fact[i - 1] * i % MOD
    inv_fact = [1] * (m + 1)
    inv_fact[m] = pow(fact[m], MOD - 2, MOD)
    for i in range(m, 0, -1):
        inv_fact[i - 1] = inv_fact[i] * i % MOD
    out = []
    for j in range(q):
        n = nums[2 * j]
        k = nums[2 * j + 1]
        if k > n:
            out.append(0)
        else:
            out.append(fact[n] * inv_fact[k] % MOD * inv_fact[n - k] % MOD)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
