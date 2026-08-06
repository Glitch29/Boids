package boids;

public record BoidArray(
        int n,
        double[] x,
        double[] y,
        int[] h,
        double tick
) {}