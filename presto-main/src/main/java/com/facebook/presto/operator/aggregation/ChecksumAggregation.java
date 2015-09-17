/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.byteCode.DynamicClassLoader;
import com.facebook.presto.metadata.FunctionInfo;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.ParametricAggregation;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.operator.aggregation.state.NullableLongState;
import com.facebook.presto.operator.aggregation.state.StateCompiler;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

import static com.facebook.presto.metadata.FunctionType.AGGREGATE;
import static com.facebook.presto.metadata.Signature.comparableTypeParameter;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.NULLABLE_BLOCK_INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static com.facebook.presto.operator.aggregation.AggregationUtils.generateAggregationName;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.util.Reflection.methodHandle;
import static io.airlift.slice.Slices.wrappedLongArray;

public class ChecksumAggregation
        extends ParametricAggregation
{
    public static final ChecksumAggregation CHECKSUM_AGGREGATION = new ChecksumAggregation();
    @VisibleForTesting
    public static final long PRIME64 = 0x9E3779B185EBCA87L;
    private static final String NAME = "checksum";
    private static final MethodHandle OUTPUT_FUNCTION = methodHandle(ChecksumAggregation.class, "output", NullableLongState.class, BlockBuilder.class);
    private static final MethodHandle INPUT_FUNCTION = methodHandle(ChecksumAggregation.class, "input", Type.class, NullableLongState.class, Block.class, int.class);
    private static final MethodHandle COMBINE_FUNCTION = methodHandle(ChecksumAggregation.class, "combine", NullableLongState.class, NullableLongState.class);
    private static final Signature SIGNATURE = new Signature(NAME, AGGREGATE, ImmutableList.of(comparableTypeParameter("T")), StandardTypes.VARBINARY, ImmutableList.of("T"), false, false);

    @Override
    public Signature getSignature()
    {
        return SIGNATURE;
    }

    @Override
    public String getDescription()
    {
        return "Checksum of the given values";
    }

    @Override
    public FunctionInfo specialize(Map<String, Type> types, int arity, TypeManager typeManager, FunctionRegistry functionRegistry)
    {
        Type valueType = types.get("T");
        Signature signature = new Signature(NAME, AGGREGATE, VARBINARY.getTypeSignature(), valueType.getTypeSignature());
        InternalAggregationFunction aggregation = generateAggregation(valueType);
        return new FunctionInfo(signature, getDescription(), aggregation);
    }

    private static InternalAggregationFunction generateAggregation(Type type)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(ChecksumAggregation.class.getClassLoader());

        List<Type> inputTypes = ImmutableList.of(type);

        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, type, inputTypes),
                createInputParameterMetadata(type),
                INPUT_FUNCTION.bindTo(type),
                null,
                null,
                COMBINE_FUNCTION,
                OUTPUT_FUNCTION,
                NullableLongState.class,
                new StateCompiler().generateStateSerializer(NullableLongState.class, classLoader),
                new StateCompiler().generateStateFactory(NullableLongState.class, classLoader),
                VARBINARY,
                false);

        GenericAccumulatorFactoryBinder factory = new AccumulatorCompiler().generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(NAME, inputTypes, BIGINT, VARBINARY, true, false, factory);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type type)
    {
        return ImmutableList.of(new ParameterMetadata(STATE), new ParameterMetadata(NULLABLE_BLOCK_INPUT_CHANNEL, type), new ParameterMetadata(BLOCK_INDEX));
    }

    public static void input(Type type, NullableLongState state, Block block, int position)
    {
        state.setNull(false);
        if (block.isNull(position)) {
            state.setLong(state.getLong() + PRIME64);
        }
        else {
            state.setLong(state.getLong() + type.hash(block, position) * PRIME64);
        }
    }

    public static void combine(NullableLongState state, NullableLongState otherState)
    {
        state.setNull(state.isNull() && otherState.isNull());
        state.setLong(state.getLong() + otherState.getLong());
    }

    public static void output(NullableLongState state, BlockBuilder out)
    {
        if (state.isNull()) {
            out.appendNull();
        }
        else {
            VARBINARY.writeSlice(out, wrappedLongArray(state.getLong()));
        }
    }
}
