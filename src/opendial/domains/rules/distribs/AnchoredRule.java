
// =================================================================                                                                   
// Copyright (C) 2011-2015 Pierre Lison (plison@ifi.uio.no)

// Permission is hereby granted, free of charge, to any person 
// obtaining a copy of this software and associated documentation 
// files (the "Software"), to deal in the Software without restriction, 
// including without limitation the rights to use, copy, modify, merge, 
// publish, distribute, sublicense, and/or sell copies of the Software, 
// and to permit persons to whom the Software is furnished to do so, 
// subject to the following conditions:

// The above copyright notice and this permission notice shall be 
// included in all copies or substantial portions of the Software.

// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
// IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY 
// CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, 
// TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE 
// SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// =================================================================                                                                   

package opendial.domains.rules.distribs;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import opendial.DialogueState;
import opendial.bn.distribs.CategoricalTable;
import opendial.bn.distribs.IndependentDistribution;
import opendial.bn.distribs.MarginalDistribution;
import opendial.bn.distribs.ProbDistribution;
import opendial.bn.distribs.UtilityFunction;
import opendial.bn.values.Value;
import opendial.datastructs.Assignment;
import opendial.datastructs.ValueRange;
import opendial.domains.rules.Rule;
import opendial.domains.rules.Rule.RuleType;
import opendial.domains.rules.RuleOutput;
import opendial.domains.rules.conditions.Condition;
import opendial.domains.rules.effects.Effect;
import opendial.domains.rules.parameters.Parameter;
import opendial.templates.Template;

/**
 * Representation of a probabilistic rule anchored in a particular dialogue state.
 * 
 * @author Pierre Lison (plison@ifi.uio.no)
 */
public final class AnchoredRule implements ProbDistribution, UtilityFunction {

	// logger
	final static Logger log = Logger.getLogger("OpenDial");

	// the rule
	String id;
	final Rule rule;

	// whether the rule is relevant
	boolean relevant = false;

	// the range of possible input values for the rule
	final ValueRange inputs;

	// the range of possible output (or action) values
	final ValueRange outputs;

	// predefined filled slots for the rule (usually empty)
	final Assignment filledSlots;

	// set of inputs and outputs for the rule
	final Set<String> variables;

	// the set of associated parameters
	final Set<String> parameters;

	// the relevant effects for the rule
	final Set<Effect> effects;

	// cache with the outputs for a given assignment
	Map<Assignment, RuleOutput> cache;

	/**
	 * Anchors the rule in the dialogue state. The construction process leads to the
	 * determination of:
	 * <ul>
	 * <li>the relevance of the rule in the given dialogue state
	 * <li>the range of possible values for the input nodes
	 * <li>the set of parameters associated with the rule
	 * <li>the set of possible effects generated by the rule
	 * <li>the set of possible values for the output nodes
	 * </ul>
	 * 
	 * @param rule the probabilistic rule
	 * @param state the dialogue state
	 * @param filled
	 */
	public AnchoredRule(Rule rule, DialogueState state, Assignment filledSlots) {
		this.rule = rule;
		this.id = rule.getRuleId();
		if (!filledSlots.isEmpty()) {
			this.id += "(" + filledSlots + ")";
		}
		effects = new HashSet<Effect>();
		outputs = new ValueRange();
		parameters = new HashSet<String>();
		this.filledSlots = filledSlots;

		// determines the input range
		inputs = new ValueRange();
		for (Template t : rule.getInputVariables()) {
			if (t.isFilledBy(filledSlots)) {
				String t2 = t.fillSlots(filledSlots).toString();
				if (state.hasChanceNode(t2)) {
					inputs.addValues(t2, state.getChanceNode(t2).getValues());
				}
			}
		}
		Set<Assignment> conditions = inputs.linearise();

		// we already start a cache if we have a probability rule
		if (rule.getRuleType() == RuleType.PROB) {
			cache = new ConcurrentHashMap<Assignment, RuleOutput>();
		}
		variables = new HashSet<String>(inputs.getVariables());

		// determines the set of possible effects, output values and parameters
		// (for all possible input values)
		for (Assignment input : conditions) {
			input.addAssignment(filledSlots);

			RuleOutput output = getCachedOutput(input);
			relevant = relevant || !output.isVoid();
			// looping on all alternative effects in the output
			for (Map.Entry<Effect, Parameter> o : output.getPairs()) {
				Effect effect = o.getKey();
				Parameter param = o.getValue();
				effects.add(effect);
				outputs.addAssign(effect.getAssignment());
				param.getVariables().stream().filter(p -> state.hasChanceNode(p))
						.forEach(p -> parameters.add(p));
			}
		}
		// adding the action variables, and activating the cache
		if (relevant && rule.getRuleType() == RuleType.UTIL) {
			variables.addAll(outputs.getVariables());
			cache = new ConcurrentHashMap<Assignment, RuleOutput>();
		}

	}

	/**
	 * Does nothing.
	 */
	@Override
	public void modifyVariableId(String oldId, String newId) {
		if (id.equals(oldId)) {
			id = newId;
		}
	}

	/**
	 * Does nothing
	 */
	@Override
	public boolean pruneValues(double threshold) {
		return false;
	}

	// ===================================
	// GETTERS
	// ===================================

	/**
	 * Returns true if the anchored rule is relevant (that is, it at least one
	 * matching rule case is non-empty), and false otherwise.
	 * 
	 * @return true if rule is relevant, and false otherwise.
	 */
	public boolean isRelevant() {
		return relevant;
	}

	/**
	 * Returns the value range for the input variables
	 * 
	 * @return the input range
	 */
	public ValueRange getInputRange() {
		return inputs;
	}

	@Override
	public Set<String> getInputVariables() {
		return inputs.getVariables();
	}

	/**
	 * Returns the output variables for the rule
	 * 
	 * @return the output variables
	 */
	public Set<String> getOutputs() {
		return outputs.getVariables();
	}

	/**
	 * Returns the value range for the output variables
	 * 
	 * @return the output range
	 */
	public ValueRange getOutputRange() {
		return outputs;
	}

	/**
	 * Returns the set of possible effects associated with the anchored rule
	 * 
	 * @return set of possible effects
	 */
	public Set<Effect> getEffects() {
		return effects;
	}

	/**
	 * Returns the rule
	 * 
	 * @return the rule
	 */
	public Rule getRule() {
		return rule;
	}

	/**
	 * Returns the set of parameter nodes for the anchored rule
	 * 
	 * @return the set of parameter nodes
	 */
	public Set<String> getParameters() {
		return parameters;
	}

	/**
	 * Returns the probability for P(head|condition), where head is an assignment of
	 * an output value for the rule node.
	 * 
	 * @param condition the conditional assignment
	 * @param head the head assignment
	 * @return the probability
	 */
	@Override
	public double getProb(Assignment condition, Value head) {
		IndependentDistribution outputTable = getProbDistrib(condition);
		double prob = outputTable.getProb(head);

		return prob;
	}

	/**
	 * Returns the utility for Q(input), where input is the assignment of values for
	 * both the chance nodes and the action nodes
	 * 
	 * @param fullInput the value assignment
	 * @return the corresponding utility
	 */
	@Override
	public double getUtil(Assignment fullInput) {

		double totalUtil = 0.0;
		RuleOutput output = getCachedOutput(fullInput);
		for (Effect effectOutput : output.getEffects()) {
			Condition effectCondition = effectOutput.convertToCondition();
			if (effectCondition.isSatisfiedBy(fullInput)) {
				Parameter param = output.getParameter(effectOutput);
				totalUtil += param.getValue(fullInput);
			}
		}
		return totalUtil;
	}

	/**
	 * Returns the probability table associated with the given input assignment
	 * 
	 * @param condition the conditional assignment
	 * @return the associated probability table (as a CategoricalTable) distribution
	 *         could not be calculated.
	 */
	@Override
	public ProbDistribution getPosterior(Assignment condition) {
		return new MarginalDistribution(this, condition);
	}

	/**
	 * Returns the possible values for the rule.
	 * 
	 */
	@Override
	public Set<Value> getValues() {
		return new HashSet<Value>(getEffects());
	}

	/**
	 * Samples one possible output value given the input assignment
	 * 
	 * @param condition the input assignment
	 * @return the sampled value
	 */
	@Override
	public Value sample(Assignment condition) {
		IndependentDistribution outputTable = getProbDistrib(condition);
		return outputTable.sample();
	}

	/**
	 * Returns the label of the anchored rule
	 * 
	 * @return the label of the anchored rule
	 */
	@Override
	public String getVariable() {
		return id;
	}

	@Override
	public IndependentDistribution getProbDistrib(Assignment input) {

		// search for the matching case
		RuleOutput output = getCachedOutput(input);

		// creating the distribution
		CategoricalTable.Builder builder = new CategoricalTable.Builder(id);

		for (Effect e : output.getEffects()) {
			Parameter param = output.getParameter(e);
			double paramValue = param.getValue(input);
			if (paramValue > 0) {
				builder.addRow(e, paramValue);
			}
		}

		if (builder.isEmpty()) {
			log.warning("probability table is empty (no effects) for " + "input "
					+ input + " and rule " + toString());
		}
		return builder.build();
	}

	// ===================================
	// UTILITY AND PRIVATE METHODS
	// ===================================

	/**
	 * Returns a copy of the distribution
	 * 
	 * @return the copy
	 */
	@Override
	public AnchoredRule copy() {
		return this;
	}

	/**
	 * Returns the pretty print for the rule
	 * 
	 * @return the pretty print
	 */
	@Override
	public String toString() {
		return rule.toString();
	}

	/**
	 * Returns the output of the anchored rule (using the cache if the input
	 * assignment is a sample).
	 * 
	 * @param input the input assignment
	 * @return the output of the rule
	 */
	private RuleOutput getCachedOutput(Assignment input) {

		if (cache == null) {
			return rule.getOutput(new Assignment(input, filledSlots));
		}
		else if (input.size() > variables.size()) {
			input = input.getTrimmed(variables);
		}
		return cache.computeIfAbsent(new Assignment(input, filledSlots),
				a -> rule.getOutput(a));
	}

}
