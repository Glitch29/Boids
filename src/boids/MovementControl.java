package boids;

public interface MovementControl {

    void calculate(Movement movement);

    class Movement {
        BoidArray boids;
        int[] movement;

        Movement(int[] x, int[] y, int[] h, long tick) {
            boids = new BoidArray(x.length, x, y, h, tick);
            movement = new int[x.length];
        }
    }
}
