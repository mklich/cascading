/*
 * Copyright (c) 2007-2012 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
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

package cascading.flow.stream;

import cascading.flow.FlowElement;
import cascading.flow.FlowProcess;
import cascading.flow.planner.Scope;
import cascading.operation.ConcreteCall;
import cascading.pipe.Operator;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.util.TupleBuilder;
import cascading.tuple.util.TupleViews;

import static cascading.tuple.util.TupleViews.*;

/**
 *
 */
public abstract class OperatorStage<Incoming> extends ElementStage<Incoming, TupleEntry>
  {
  protected ConcreteCall operationCall;
  protected TupleEntry incomingEntry;
  protected Fields argumentsSelector;
  protected TupleEntry argumentsEntry;
  protected Fields remainderFields;
  protected Fields outgoingSelector;
  protected TupleEntry outgoingEntry;

  protected TupleBuilder argumentsBuilder;
  protected TupleBuilder outgoingBuilder;

  protected TupleEntryCollector outputCollector;

  public OperatorStage( FlowProcess flowProcess, FlowElement flowElement )
    {
    super( flowProcess, flowElement );
    }

  public abstract Operator getOperator();

  protected abstract Fields getOutgoingSelector();

  protected Fields getOperationDeclaredFields()
    {
    return outgoingScopes.get( 0 ).getOperationDeclaredFields();
    }

  protected abstract Fields getIncomingPassThroughFields();

  protected abstract Fields getIncomingArgumentsFields();

  protected TupleBuilder createArgumentsBuilder( final Fields incomingFields, final Fields argumentsSelector )
    {
    if( incomingFields.isUnknown() )
      return new TupleBuilder()
      {
      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return input.get( incomingFields, argumentsSelector );
        }
      };

    if( argumentsSelector.isAll() )
      return new TupleBuilder()
      {
      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return input;
        }
      };

    if( argumentsSelector.isNone() )
      return new TupleBuilder()
      {
      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return Tuple.NULL;
        }
      };

    final Fields inputDeclarationFields = Fields.asDeclaration( incomingFields );

    return new TupleBuilder()
    {
    Tuple result = createNarrow( inputDeclarationFields.getPos( argumentsSelector ) );

    @Override
    public Tuple makeResult( Tuple input, Tuple output )
      {
      return TupleViews.reset( result, input );
      }
    };
    }

  protected TupleBuilder createOutgoingBuilder( final Operator operator, final Fields incomingFields, final Fields argumentSelector, final Fields remainderFields, final Fields declaredFields, final Fields outgoingSelector )
    {
    final Fields inputDeclarationFields = Fields.asDeclaration( incomingFields );

    if( operator.getOutputSelector().isResults() )
      return new TupleBuilder()
      {
      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return output;
        }
      };

    if( operator.getOutputSelector().isAll() && !( incomingFields.isUnknown() || declaredFields.isUnknown() ) )
      return new TupleBuilder()
      {
      Tuple result = createComposite( inputDeclarationFields, declaredFields );

      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return TupleViews.reset( result, input, output );
        }
      };

    if( operator.getOutputSelector().isReplace() )
      return new TupleBuilder()
      {
      // todo: test when arg and decl are not the same?
      Fields resultFields = operator.getFieldDeclaration().isArguments() ? argumentSelector : declaredFields;
      Tuple result = createOverride( inputDeclarationFields, resultFields );

      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        return TupleViews.reset( result, input, output );
        }
      };

    if( operator.getOutputSelector().isSwap() )
      {
      if( remainderFields.size() == 0 ) // the same as Fields.RESULTS
        return new TupleBuilder()
        {
        @Override
        public Tuple makeResult( Tuple input, Tuple output )
          {
          return output;
          }
        };
      else if( declaredFields.isUnknown() )
        return new TupleBuilder()
        {
        @Override
        public Tuple makeResult( Tuple input, Tuple output )
          {
          return input.get( incomingFields, remainderFields ).append( output );
          }
        };
      else
        return new TupleBuilder()
        {
        Tuple view = createNarrow( inputDeclarationFields.getPos( remainderFields ) );
        Tuple result = createComposite( Fields.asDeclaration( remainderFields ), declaredFields );

        @Override
        public Tuple makeResult( Tuple input, Tuple output )
          {
          TupleViews.reset( view, input );

          return TupleViews.reset( result, view, output );
          }
        };
      }

    if( incomingFields.isUnknown() || declaredFields.isUnknown() )
      return new TupleBuilder()
      {
      Fields selector = outgoingSelector.isUnknown() ? Fields.ALL : outgoingSelector;
      TupleEntry incoming = new TupleEntry( incomingFields );
      TupleEntry declared = new TupleEntry( declaredFields );

      @Override
      public Tuple makeResult( Tuple input, Tuple output )
        {
        incoming.setTuple( input );
        declared.setTuple( output );

        return TupleEntry.select( selector, incoming, declared );
        }
      };

    return new TupleBuilder()
    {
    Fields inputFields = operator.getFieldDeclaration().isArguments() ? Fields.mask( inputDeclarationFields, declaredFields ) : inputDeclarationFields;
    Tuple appended = createComposite( inputFields, declaredFields );
    Fields allFields = Fields.resolve( Fields.ALL, inputFields, declaredFields );
    Tuple result = createNarrow( allFields.getPos( outgoingSelector ), appended );


    @Override
    public Tuple makeResult( Tuple input, Tuple output )
      {
      TupleViews.reset( appended, input, output );

      return result;
      }
    };
    }

  @Override
  public void initialize()
    {
    Scope outgoingScope = outgoingScopes.get( 0 );

    operationCall = new ConcreteCall( outgoingScope.getArgumentsDeclarator() );

    argumentsSelector = outgoingScope.getArgumentsSelector();
    remainderFields = outgoingScope.getRemainderPassThroughFields();
    outgoingSelector = getOutgoingSelector();

    argumentsEntry = new TupleEntry( outgoingScope.getArgumentsDeclarator(), true );

    outgoingEntry = new TupleEntry( getOutgoingFields(), true );  // todo: simplify this

    operationCall.setArguments( argumentsEntry );

    argumentsBuilder = createArgumentsBuilder( getIncomingArgumentsFields(), argumentsSelector );
    outgoingBuilder = createOutgoingBuilder( getOperator(), getIncomingPassThroughFields(), argumentsSelector, remainderFields, getOperationDeclaredFields(), outgoingSelector );
    }

  @Override
  public void prepare()
    {
    super.prepare(); // if fails, skip the this prepare

    ( (Operator) getFlowElement() ).getOperation().prepare( flowProcess, operationCall );
    }

  @Override
  public void complete( Duct previous )
    {
    try
      {
      ( (Operator) getFlowElement() ).getOperation().flush( flowProcess, operationCall );
      }
    finally
      {
      super.complete( previous );
      }
    }

  @Override
  public void cleanup()
    {
    try
      {
      ( (Operator) getFlowElement() ).getOperation().cleanup( flowProcess, operationCall );
      }
    finally
      {
      super.cleanup(); // guarantee this happens
      }
    }
  }
