package io.github.elguardian.mcpsettools.analyze.detect;

import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.analyze.Model;

/** A bug detector. Detectors that need static or runtime input return nothing without it. */
public interface Detector {

    /** Short id used to select the detector, e.g. {@code resources}. */
    String id();

    /** Rule ids this detector reports, with a one-line description each. */
    Map<String, String> rules();

    List<Finding> run(Model model);
}
