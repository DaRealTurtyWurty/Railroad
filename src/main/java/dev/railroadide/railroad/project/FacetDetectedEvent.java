package dev.railroadide.railroad.project;

import dev.railroadide.railroad.project.facet.Facet;
import dev.railroadide.railroadpluginapi.event.Event;

public record FacetDetectedEvent(Project project, Facet<?> facet) implements Event {
}
