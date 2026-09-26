// 스레드를 상한보다 많이 만든다. JVM 은 "OutOfMemoryError: unable to create native thread" 로 죽는다 -
// 메모리가 아니라 프로세스 수 상한(pids-limit)이므로 RUNTIME_ERROR 여야 한다.
public class Main {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 500; i++) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    // 끝
                }
            });
            t.setDaemon(true);
            t.start();
        }
        System.out.println("spawned");
    }
}
