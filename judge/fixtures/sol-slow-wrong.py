# 매 case 마다 느리게 돌다가 틀린다. 제한에는 걸리지 않는다.
#
# "싼 실패" 라고 해서 case 가 빠른 것은 아니라는 것을 보이는 fixture 다.
# 실패한 뒤 남은 case 를 계속 돌릴 때 예산이 없으면 이런 제출이 hard timeout 까지 간다.
import sys
import time

sys.stdin.read()
time.sleep(0.3)
print("틀린 답")
