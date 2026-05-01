# Datasets

This directory is reserved for the datasets used by the paper's
experiments.

## Status

**TODO:** Synthetic graph generation scripts are not yet bundled. The
paper's Experiment 5 used Zipfian (power-law) synthetic graphs at
hyperscale. The generator and the parameters needed to reproduce those
graphs are still being prepared and will be added here.

Likewise, the smaller graphs used to seed Experiments 1-4 (linear chains,
high-fanout DAGs, disconnected components, k-core stress inputs) are
generated programmatically by the Java drivers in `experiments/java/`;
each driver creates and populates its own input tables at startup. Where
that is not the case, a separate generator will be added here.
