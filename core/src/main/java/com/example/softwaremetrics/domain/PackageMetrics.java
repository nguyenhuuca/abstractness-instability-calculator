package com.example.softwaremetrics.domain;

import java.util.List;

@SuppressWarnings("unused")
public class PackageMetrics {
    private String packageName;
    private int ce;
    private List<String> efferentDependencies;
    private int ca;
    private List<String> afferentDependencies;
    private int abstractClassCount;
    private int totalClassCount;
    private double abstractness;
    private double instability;
    private double distance;
    private int methodCount;
    private double avgComplexity;
    private int maxComplexity;
    private String mostComplexMethod;

    // Constructor
    public PackageMetrics() {}

    // Getters and setters
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public int getCe() { return ce; }
    public void setCe(int ce) { this.ce = ce; }

    public List<String> getEfferentDependencies() { return efferentDependencies; }
    public void setEfferentDependencies(List<String> efferentDependencies) { this.efferentDependencies = efferentDependencies; }

    public int getCa() { return ca; }
    public void setCa(int ca) { this.ca = ca; }

    public List<String> getAfferentDependencies() { return afferentDependencies; }
    public void setAfferentDependencies(List<String> afferentDependencies) { this.afferentDependencies = afferentDependencies; }

    public int getAbstractClassCount() { return abstractClassCount; }
    public void setAbstractClassCount(int abstractClassCount) { this.abstractClassCount = abstractClassCount; }

    public int getTotalClassCount() { return totalClassCount; }
    public void setTotalClassCount(int totalClassCount) { this.totalClassCount = totalClassCount; }

    public double getAbstractness() { return abstractness; }
    public void setAbstractness(double abstractness) { this.abstractness = abstractness; }

    public double getInstability() { return instability; }
    public void setInstability(double instability) { this.instability = instability; }

    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }

    public int getMethodCount() { return methodCount; }
    public void setMethodCount(int methodCount) { this.methodCount = methodCount; }

    public double getAvgComplexity() { return avgComplexity; }
    public void setAvgComplexity(double avgComplexity) { this.avgComplexity = avgComplexity; }

    public int getMaxComplexity() { return maxComplexity; }
    public void setMaxComplexity(int maxComplexity) { this.maxComplexity = maxComplexity; }

    public String getMostComplexMethod() { return mostComplexMethod; }
    public void setMostComplexMethod(String mostComplexMethod) { this.mostComplexMethod = mostComplexMethod; }
}