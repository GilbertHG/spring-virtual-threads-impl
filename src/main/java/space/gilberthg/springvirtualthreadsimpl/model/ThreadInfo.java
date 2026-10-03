package space.gilberthg.springvirtualthreadsimpl.model;

public record ThreadInfo(boolean virtual, String name) {

    public static ThreadInfo current() {
        Thread thread = Thread.currentThread();
        return new ThreadInfo(thread.isVirtual(), thread.getName());
    }

}
