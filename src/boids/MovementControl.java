package boids;

public interface MovementControl {

    void calculate(Movement movement);

    class Movement {
        BoidArray boids;
        int[] movement;

        Movement(double[] x, double[] y, int[] h, long tick) {
            boids = new BoidArray(x.length, x, y, h, tick);
            movement = new int[x.length];
        }
    }
}
