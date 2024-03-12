package de.tomalbrc.bil.file.bbmodel;

import com.google.gson.annotations.SerializedName;
import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.ReferenceType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BbKeyframe {
    public Channel channel;
    @SerializedName("data_points")
    public List<Map<String, DataPointValue>> dataPoints;

    public UUID uuid;
    public float time;
    public int color;
    public String interpolation;
    @SerializedName("bezier_linked")
    public boolean bezierLinked;
    @SerializedName("bezier_left_time")
    public List<Float> bezierLeftTime;
    @SerializedName("bezier_left_value")
    public List<Float> bezierLeftValue;
    @SerializedName("bezier_right_time")
    public List<Float> bezierRightTime;
    @SerializedName("bezier_right_value")
    public List<Float> bezierRightValue;

    public enum Channel {
        position,
        rotation,
        scale
    }

    static class Suu {
        final public float time;

        Suu(float time) {
            this.time = time;
        }
    }

    static public class DataPointValue {
        public float value;
        public String expression;

        public float getValue(BbVariablePlaceholders placeholders, float time) {
            if (expression == null)
                return value;

            String modifiedExpression = expression;
            if (placeholders != null)
                modifiedExpression = placeholders.substituteVariables(expression).replace("\n", " ").trim();

            MolangExpression molangExpression = null;
            try {
                molangExpression = MolangParser.parse(modifiedExpression);
            } catch (MolangLexException ex) {
                throw new RuntimeException(ex);
            } catch (MolangParseException ex) {
                throw new RuntimeException(ex);
            }

            return molangExpression.bind(ExpressionBindingContext
                    .create()
                    .registerDirectReferenceResolver(ReferenceType.QUERY, "anim_time", () -> time)
                    .registerDirectReferenceResolver(ReferenceType.QUERY, "life_time", () -> time)
            ).evaluate();
        }
    }
}