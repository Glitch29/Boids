package boids;

public record BoidArray(
        int n,
        int[] x,
        int[] y,
        int[] h,
        double tick
) {}