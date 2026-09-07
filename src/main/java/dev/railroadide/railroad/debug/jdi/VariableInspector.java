package dev.railroadide.railroad.debug.jdi;

import com.sun.jdi.*;
import dev.railroadide.railroad.debug.model.DebugVariable;

import java.util.*;

public final class VariableInspector {
    private long generation;
    private long nextReference = -1;

    private final Map<Long, Value> references = new HashMap<>();

    public void beginSuspension(long generation) {
        this.generation = generation;
        this.nextReference = 1;
        references.clear();
    }

    public void endSuspension() {
        references.clear();
    }

    public List<DebugVariable> inspectFrame(StackFrame frame) {
        List<DebugVariable> variables = new ArrayList<>();

        ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            variables.add(createVariable(
                "this",
                thisObject.referenceType().name(),
                thisObject));
        }

        try {
            List<LocalVariable> localVariables = frame.visibleVariables();
            Map<LocalVariable, Value> localVariableValues = frame.getValues(localVariables);
            for (LocalVariable localVariable : localVariables) {
                variables.add(createVariable(
                    localVariable.name(),
                    localVariable.typeName(),
                    localVariableValues.get(localVariable)));
            }
        } catch (AbsentInformationException _) {
            /*
             * No local-variable table.
             *
             * We can still expose method arguments without
             * their original names.
             */

            List<Value> arguments = frame.getArgumentValues();

            for (int index = 0; index < arguments.size(); index++) {
                variables.add(
                    createVariable(
                        "arg" + index,
                        typeOf(arguments.get(index)),
                        arguments.get(index)));
            }
        }

        return variables;
    }

    public DebugVariable createVariable(String name, String declaredType, Value value) {
        if (value == null)
            return new DebugVariable(
                name,
                declaredType,
                "null",
                0,
                0);

        long childrenReference = 0;
        int indexedChildren = 0;

        if (value instanceof ArrayReference array) {
            childrenReference = storeReference(value);
            indexedChildren = array.length();
        } else if (value instanceof ObjectReference && !(value instanceof StringReference)) {
            childrenReference = storeReference(value);
        }

        return new DebugVariable(
            name,
            declaredType,
            formatValue(value),
            childrenReference,
            indexedChildren);
    }

    private long storeReference(Value value) {
        long id = nextReference++;
        references.put(id, value);
        return id;
    }

    public List<DebugVariable> expand(long reference, int start, int count) {
        Value value = references.get(reference);

        return switch (value) {
            case null -> throw new IllegalArgumentException("Unknown or expired variable reference");
            case ArrayReference array -> expandArray(array, start, count);
            case ObjectReference object -> expandObject(object);
            default -> List.of();
        };
    }

    private List<DebugVariable> expandArray(ArrayReference array, int start, int count) {
        int actualStart = Math.max(0, start);
        int actualCount = Math.min(count, array.length() - actualStart);
        if (actualCount <= 0)
            return List.of();

        List<Value> values = array.getValues(actualStart, actualCount);
        List<DebugVariable> variables = new ArrayList<>(values.size());

        for (int i = 0; i < values.size(); i++) {
            Value value = values.get(i);

            variables.add(createVariable(
                "[" + (actualStart + i) + "]",
                typeOf(value),
                value));
        }

        return variables;
    }

    private List<DebugVariable> expandObject(ObjectReference object) {
        List<Field> fields = object.referenceType()
            .visibleFields()
            .stream()
            .filter(field -> !field.isStatic())
            .sorted(
                Comparator.comparing(
                    Field::name))
            .toList();

        Map<Field, Value> values = object.getValues(fields);
        List<DebugVariable> variables = new ArrayList<>();

        for (Field field : fields) {
            variables.add(createVariable(
                field.name(),
                field.typeName(),
                values.get(field)));
        }

        return variables;
    }

    private String formatValue(Value value) {
        switch (value) {
            case null -> {
                return "null";
            }
            case StringReference string -> {
                String text = string.value();

                if (text.length() > 200) {
                    text = text.substring(0, 200) + "…";
                }

                return "\"" + escape(text) + "\"";
            }
            case CharValue character -> {
                return "'" + character.value() + "'";
            }
            case BooleanValue booleanValue -> {
                return Boolean.toString(
                    booleanValue.value());
            }
            case ByteValue number -> {
                return Byte.toString(number.value());
            }
            case ShortValue number -> {
                return Short.toString(number.value());
            }
            case IntegerValue number -> {
                return Integer.toString(number.value());
            }
            case LongValue number -> {
                return number.value() + "L";
            }
            case FloatValue number -> {
                return number.value() + "f";
            }
            case DoubleValue number -> {
                return Double.toString(number.value());
            }
            case ArrayReference array -> {
                return array.referenceType().name()
                    + "[length="
                    + array.length()
                    + "]";
            }
            case ObjectReference object -> {
                return object.referenceType().name()
                    + "@"
                    + Long.toHexString(
                        object.uniqueID());
            }
            default -> {
            }
        }

        return String.valueOf(value);
    }

    private String typeOf(Value value) {
        return value == null
            ? "null"
            : value.type().name();
    }

    private String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\"", "\\\"");
    }
}
