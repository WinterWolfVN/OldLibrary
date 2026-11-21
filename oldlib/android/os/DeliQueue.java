package oldlib.android.os;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.concurrent.atomic.AtomicReference;

// Support Android 5+
public final class DeliQueue implements Runnable {
    private static final DeliQueue INSTANCE = new DeliQueue();
    private static final Looper MAIN_LOOPER = Looper.getMainLooper();
    private static final Handler MAIN_HANDLER = new Handler(MAIN_LOOPER);

    private final AtomicReference<TaskNode> mHead = new AtomicReference<>(null);

    private static final class TaskNode {
        final Runnable task;
        TaskNode next;
        TaskNode(Runnable task) {
            this.task = task;
        }
    }

    private DeliQueue() {}

    public static void post(Runnable r) {
        enqueue(r, false);
    }

    public static void postAsync(Runnable r) {
        enqueue(r, true);
    }

    public static void execute(Runnable r) {
        if (Looper.myLooper() == MAIN_LOOPER) {
            r.run();
        } else {
            enqueue(r, true);
        }
    }

    private static void enqueue(Runnable r, boolean async) {
        if (r == null) throw new NullPointerException("Runnable cannot be null");
        TaskNode node = new TaskNode(r);
        AtomicReference<TaskNode> head = INSTANCE.mHead;
        TaskNode oldHead;
        do {
            oldHead = head.get();
            node.next = oldHead;
        } while (!head.compareAndSet(oldHead, node));

        if (oldHead == null) {
            Message msg = Message.obtain(MAIN_HANDLER, INSTANCE);
            if (async) {
                msg.setAsynchronous(true);
            }
            MAIN_HANDLER.sendMessage(msg);
        }
    }

    @Override
    public void run() {
        TaskNode curr = mHead.getAndSet(null);
        TaskNode prev = null;
        while (curr != null) {
            TaskNode next = curr.next;
            curr.next = prev;
            prev = curr;
            curr = next;
        }
        while (prev != null) {
            prev.task.run();
            prev = prev.next;
        }
    }
}