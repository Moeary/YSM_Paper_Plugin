package com.ysm.paper.nativebridge.crypto;

import java.util.List;
import java.util.Optional;

public record YsmModelProfile(
        String metadataName,
        String defaultTexture,
        String previewAnimation,
        List<ExtraAnimation> extraAnimations,
        List<ExtraAnimationButton> extraAnimationButtons,
        List<ExtraAnimationClassify> extraAnimationClassifies) {
    public static final YsmModelProfile EMPTY = new YsmModelProfile(
            "",
            "",
            "",
            List.of(),
            List.of(),
            List.of());

    public YsmModelProfile {
        metadataName = metadataName == null ? "" : metadataName;
        defaultTexture = defaultTexture == null ? "" : defaultTexture;
        previewAnimation = previewAnimation == null ? "" : previewAnimation;
        extraAnimations = List.copyOf(extraAnimations == null ? List.of() : extraAnimations);
        extraAnimationButtons = List.copyOf(extraAnimationButtons == null ? List.of() : extraAnimationButtons);
        extraAnimationClassifies = List.copyOf(extraAnimationClassifies == null ? List.of() : extraAnimationClassifies);
    }

    public Optional<ExtraAnimation> extraAnimationAt(int index) {
        if (index < 0 || index >= extraAnimations.size()) {
            return Optional.empty();
        }
        return Optional.of(extraAnimations.get(index));
    }

    public Optional<ExtraAnimationButton> extraAnimationButtonAt(int index) {
        if (index < 0 || index >= extraAnimationButtons.size()) {
            return Optional.empty();
        }
        return Optional.of(extraAnimationButtons.get(index));
    }

    public boolean hasAnimationMapping() {
        return !extraAnimations.isEmpty() || !extraAnimationButtons.isEmpty();
    }

    public String compact() {
        return "metadataName=" + display(metadataName)
                + ", extraAnimations=" + extraAnimations.size()
                + ", buttons=" + extraAnimationButtons.size()
                + ", defaultTexture=" + display(defaultTexture)
                + ", previewAnimation=" + display(previewAnimation);
    }

    public String animationDebugSummary(int limit) {
        int max = Math.max(0, limit);
        return "extraAnimations=" + summarizeExtraAnimations(max)
                + ", buttons=" + summarizeButtons(max);
    }

    private String summarizeExtraAnimations(int limit) {
        if (extraAnimations.isEmpty() || limit == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        int count = Math.min(limit, extraAnimations.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            ExtraAnimation animation = extraAnimations.get(i);
            builder.append(i).append(':').append(display(animation.name()));
            if (!animation.value().isEmpty()) {
                builder.append("=>").append(display(animation.value()));
            }
        }
        if (extraAnimations.size() > count) {
            builder.append(", ... +").append(extraAnimations.size() - count);
        }
        return builder.append(']').toString();
    }

    private String summarizeButtons(int limit) {
        if (extraAnimationButtons.isEmpty() || limit == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        int count = Math.min(limit, extraAnimationButtons.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            ExtraAnimationButton button = extraAnimationButtons.get(i);
            builder.append(i).append(':').append(display(button.id()));
            if (!button.name().isEmpty() && !button.name().equals(button.id())) {
                builder.append('/').append(display(button.name()));
            }
        }
        if (extraAnimationButtons.size() > count) {
            builder.append(", ... +").append(extraAnimationButtons.size() - count);
        }
        return builder.append(']').toString();
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "<empty>" : '"' + value + '"';
    }

    public record ExtraAnimation(String name, String value) {
        public ExtraAnimation {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
        }
    }

    public record ExtraAnimationButton(String id, String name, List<ButtonForm> forms) {
        public ExtraAnimationButton {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            forms = List.copyOf(forms == null ? List.of() : forms);
        }
    }

    public record ButtonForm(
            String type,
            String title,
            String description,
            String value,
            float step,
            float min,
            float max,
            List<ExtraAnimation> labels) {
        public ButtonForm {
            type = type == null ? "" : type;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            value = value == null ? "" : value;
            labels = List.copyOf(labels == null ? List.of() : labels);
        }
    }

    public record ExtraAnimationClassify(String id, List<ExtraAnimation> animations) {
        public ExtraAnimationClassify {
            id = id == null ? "" : id;
            animations = List.copyOf(animations == null ? List.of() : animations);
        }
    }
}
