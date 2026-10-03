package space.gilberthg.springvirtualthreadsimpl.model;

import java.util.List;

public record Report(List<Section> sections, ThreadInfo requestThread, long elapsedMillis) {
}
