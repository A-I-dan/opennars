/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.opennars.inference;

import org.opennars.control.DerivationContext;
import org.opennars.entity.BudgetValue;
import org.opennars.entity.Sentence;
import org.opennars.entity.Task;
import org.opennars.entity.TruthValue;
import org.opennars.io.Symbols;
import org.opennars.language.*;
import org.opennars.main.Parameters;
import org.opennars.storage.Memory;

import java.util.List;

/**
 * Single-premise inference rules involving compound terms. Input are one
 * sentence (the premise) and one TermLink (indicating a component)
 */
public final class StructuralRules {

   

    /* -------------------- transform between compounds and term -------------------- */
    /**
     * {<S --> P>, S@(S&T)} |- <(S&T) --> (P&T)> {<S --> P>, S@(M-S)} |- <(M-P)
     * --> (M-S)>
     *
     * @param compound The compound term
     * @param index The location of the indicated term in the compound
     * @param statement The premise
     * @param side The location of the indicated term in the premise
     * @param nal Reference to the memory
     */
    static void structuralCompose2(final CompoundTerm compound, final short index, final Statement statement, final short side, final DerivationContext nal) {
        if (compound.equals(statement.term[side])) {
            return;
        }
        Term sub = statement.getSubject();
        Term pred = statement.getPredicate();
        final List<Term> components = compound.asTermList();
        if (((side == 0) && components.contains(pred)) || ((side == 1) && components.contains(sub))) {
            return;
        }
        if (side == 0) {
            if (components.contains(sub)) {
                sub = compound;
                components.set(index, pred);
                pred = Terms.term(compound, components);
            }
        } else {
            if (components.contains(pred)) {
                components.set(index, sub);
                sub = Terms.term(compound, components);
                pred = compound;
            }
        }
        
        if ((sub == null) || (pred == null))
            return;
        
        final Statement content;
        final int order = statement.getTemporalOrder();
        if (switchOrder(compound, index)) {
            content = Statement.make(statement, pred, sub, TemporalRules.reverseOrder(order));
        } else {
            content = Statement.make(statement, sub, pred, order);
        }
        
        if (content == null)
            return;
        
        final Sentence sentence = nal.getCurrentTask().sentence;
        final TruthValue truth = TruthFunctions.deduction(sentence.truth, Parameters.reliance);
        final BudgetValue budget = BudgetFunctions.compoundForward(truth, content, nal);
        nal.singlePremiseTask(content, truth, budget);
    }

    /**
     * {<(S*T) --> (P*T)>, S@(S*T)} |- <S --> P>
     *
     * @param statement The premise
     * @param nal Reference to the memory
     */
    static void structuralDecompose2(final Statement statement, final int index, final DerivationContext nal) {
        final Term subj = statement.getSubject();
        final Term pred = statement.getPredicate();
        if (subj.getClass() != pred.getClass()) {
            return;
        }
        
        if (!(subj instanceof Product) && !(subj instanceof SetExt) && !(subj instanceof SetInt)) {
            return; // no abduction on other compounds for now, but may change in the future
        }
        
        final CompoundTerm sub = (CompoundTerm) subj;
        final CompoundTerm pre = (CompoundTerm) pred;
        if (sub.size() != pre.size() || sub.size() <= index) {
            return;
        }
        
        final Term t1 = sub.term[index];
        final Term t2 = pre.term[index];
        final Statement content;
        final int order = statement.getTemporalOrder();
        if (switchOrder(sub, (short) index)) {
            content = Statement.make(statement, t2, t1, TemporalRules.reverseOrder(order));
        } else {
            content = Statement.make(statement, t1, t2, order);
        }
        if (content == null) {
            return;
        }
        final Task task = nal.getCurrentTask();
        final Sentence sentence = task.sentence;
        final TruthValue truth = sentence.truth;
        final BudgetValue budget;
        if (sentence.isQuestion() || sentence.isQuest()) {
            budget = BudgetFunctions.compoundBackward(content, nal);
        } else {
            budget = BudgetFunctions.compoundForward(truth, content, nal);
        }
        nal.singlePremiseTask(content, truth, budget);
    }

    /**
     * List the cases where the direction of inheritance is revised in
     * conclusion
     *
     * @param compound The compound term
     * @param index The location of focus in the compound
     * @return Whether the direction of inheritance should be revised
     */
    private static boolean switchOrder(final CompoundTerm compound, final short index) {
        return ((((compound instanceof DifferenceExt) || (compound instanceof DifferenceInt)) && (index == 1))
                || ((compound instanceof ImageExt) && (index != ((ImageExt) compound).relationIndex))
                || ((compound instanceof ImageInt) && (index != ((ImageInt) compound).relationIndex)));
    }

    /**
     * {<S --> P>, P@(P|Q)} |- <S --> (P|Q)>
     *
     * @param compound The compound term
     * @param index The location of the indicated term in the compound
     * @param statement The premise
     * @param nal Reference to the memory
     */
    static void structuralCompose1(final CompoundTerm compound, final short index, final Statement statement, final DerivationContext nal) {
        if (!nal.getCurrentTask().sentence.isJudgment()) {
            return;     // forward inference only
        }
        final Term component = compound.term[index];
        final Task task = nal.getCurrentTask();
        final Sentence sentence = task.sentence;
        final int order = sentence.getTemporalOrder();
        final TruthValue truth = sentence.truth;
        
        final float reliance = Parameters.reliance;
        final TruthValue truthDed = TruthFunctions.deduction(truth, reliance);
        final TruthValue truthNDed = TruthFunctions.negation(TruthFunctions.deduction(truth, reliance));
        
        final Term subj = statement.getSubject();
        final Term pred = statement.getPredicate();
        
        if (component.equals(subj)) {
            if (compound instanceof IntersectionExt) {
                structuralStatement(compound, pred, order, truthDed, nal);
            } else if (compound instanceof IntersectionInt) {
            } else if ((compound instanceof DifferenceExt) && (index == 0)) {
                structuralStatement(compound, pred, order, truthDed, nal);
            } else if (compound instanceof DifferenceInt) {
                if (index == 0) {
                } else {
                    structuralStatement(compound, pred, order, truthNDed, nal);
                }
            }
        } else if (component.equals(pred)) {
            if (compound instanceof IntersectionExt) {
            } else if (compound instanceof IntersectionInt) {
                structuralStatement(subj, compound, order, truthDed, nal);
            } else if (compound instanceof DifferenceExt) {
                if (index == 0) {
                } else {
                    structuralStatement(subj, compound, order, truthNDed, nal);
                }
            } else if ((compound instanceof DifferenceInt) && (index == 0)) {
                structuralStatement(subj, compound, order, truthDed, nal);
            }
        }
    }

    /**
     * {<(S|T) --> P>, S@(S|T)} |- <S --> P> {<S --> (P&T)>, P@(P&T)} |- <S --> P>
     *
     * @param compound The compound term
     * @param index The location of the indicated term in the compound
     * @param statement The premise
     * @param nal Reference to the memory
     */
    static void structuralDecompose1(final CompoundTerm compound, final short index, final Statement statement, final DerivationContext nal) {
        if(index >= compound.term.length) {
            return;
        }
        final Term component = compound.term[index];
        final Task task = nal.getCurrentTask();
        final Sentence sentence = task.sentence;
        final int order = sentence.getTemporalOrder();
        final TruthValue truth = sentence.truth;
        
        if (truth == null) {
            return;
        }
        
        final float reliance = Parameters.reliance;
        final TruthValue truthDed = TruthFunctions.deduction(truth, reliance);
        final TruthValue truthNDed = TruthFunctions.negation(TruthFunctions.deduction(truth, reliance));
        
        final Term subj = statement.getSubject();
        final Term pred = statement.getPredicate();
        if (compound.equals(subj)) {
            if (compound instanceof IntersectionInt) {
                structuralStatement(component, pred, order, truthDed, nal);
            } else if ((compound instanceof SetExt) && (compound.size() > 1)) {
                final Term[] t1 = new Term[]{component};
                structuralStatement(new SetExt(t1), pred, order, truthDed, nal);
            } else if (compound instanceof DifferenceInt) {
                if (index == 0) {
                    structuralStatement(component, pred, order, truthDed, nal);
                } else {
                    structuralStatement(component, pred, order, truthNDed, nal);
                }
            }
        } else if (compound.equals(pred)) {
            if (compound instanceof IntersectionExt) {
                structuralStatement(subj, component, order, truthDed, nal);
            } else if ((compound instanceof SetInt) && (compound.size() > 1)) {
                structuralStatement(subj, new SetInt(component), order, truthDed, nal);
            } else if (compound instanceof DifferenceExt) {
                if (index == 0) {
                    structuralStatement(subj, component, order, truthDed, nal);
                } else {
                    structuralStatement(subj, component, order, truthNDed, nal);
                }
            }
        }
    }

    /**
     * Common final operations of the above two methods
     *
     * @param subject The subject of the new task
     * @param predicate The predicate of the new task
     * @param truth The truth value of the new task
     * @param nal Reference to the memory
     */
    private static void structuralStatement(final Term subject, final Term predicate, final int order, final TruthValue truth, final DerivationContext nal) {
        final Task task = nal.getCurrentTask();
        final Term oldContent = task.getTerm();
        if (oldContent instanceof Statement) {
            final Statement content = Statement.make((Statement) oldContent, subject, predicate, order);
            if (content != null) {
                final BudgetValue budget = BudgetFunctions.compoundForward(truth, content, nal);
                nal.singlePremiseTask(content, truth, budget);
            }
        }
    }

    /* -------------------- set transform -------------------- */
    /**
     * {<S --> {P}>} |- <S <-> {P}>
     *
     * @param compound The set compound
     * @param statement The premise
     * @param side The location of the indicated term in the premise
     * @param nal Reference to the memory
     */
    static void transformSetRelation(final CompoundTerm compound, final Statement statement, final short side, final DerivationContext nal) {
        if (compound.size() > 1) {
            return;
        }
        if (statement instanceof Inheritance) {
            if (((compound instanceof SetExt) && (side == 0)) || ((compound instanceof SetInt) && (side == 1))) {
                return;
            }
        }
        final Term sub = statement.getSubject();
        final Term pre = statement.getPredicate();
        final Statement content;
        if (statement instanceof Inheritance) {
            content = Similarity.make(sub, pre);
        } else {
            if (((compound instanceof SetExt) && (side == 0)) || ((compound instanceof SetInt) && (side == 1))) {
                content = Inheritance.make(pre, sub);
            } else {
                content = Inheritance.make(sub, pre);
            }
        }
        if (content == null) {
            return;
        }

        final Task task = nal.getCurrentTask();
        final Sentence sentence = task.sentence;
        final TruthValue truth = sentence.truth;
        final BudgetValue budget;
        if (sentence.isJudgment()) {
            budget = BudgetFunctions.compoundForward(truth, content, nal);
        } else {
            budget = BudgetFunctions.compoundBackward(content, nal);
        }
        nal.singlePremiseTask(content, truth, budget);
    }

    /* -------------------- products and images transform -------------------- */
    /**
     * Equivalent transformation between products and images {<(*, S, M) --> P>,
     * S@(*, S, M)} |- <S --> (/, P, _, M)> {<S --> (/, P, _, M)>, P@(/, P, _,
     * M)} |- <(*, S, M) --> P> {<S --> (/, P, _, M)>, M@(/, P, _, M)} |- <M -->
     * (/, P, S, _)>
     *
     * @param inh An Inheritance statement
     * @param oldContent The whole content
     * @param indices The indices of the TaskLink
     * @param nal Reference to the memory
     */
    static void transformProductImage(final Inheritance inh, final CompoundTerm oldContent, final short[] indices, final DerivationContext nal) {
        final Memory memory = nal.mem();
        Term subject = inh.getSubject();
        Term predicate = inh.getPredicate();
        final short index = indices[indices.length - 1];
        final short side = indices[indices.length - 2];
        if (inh.equals(oldContent)) {
            if (subject instanceof CompoundTerm) {
                transformSubjectPI(index, (CompoundTerm) subject, predicate, nal);
            }
            if (predicate instanceof CompoundTerm) {
                transformPredicatePI(index, subject, (CompoundTerm) predicate, nal);
            }
            return;
        }

        final Term compT = inh.term[side];
        if (!(compT instanceof CompoundTerm))
            return;
        final CompoundTerm comp = (CompoundTerm)compT;
        
        if (comp instanceof Product) {
            if (side == 0) {
                subject = comp.term[index];
                predicate = ImageExt.make((Product) comp, inh.getPredicate(), index);
            } else {
                subject = ImageInt.make((Product) comp, inh.getSubject(), index);
                predicate = comp.term[index];
            }
        } else if ((comp instanceof ImageExt) && (side == 1)) {
            if (index == ((ImageExt) comp).relationIndex) {
                subject = Product.make(comp, inh.getSubject(), index);
                predicate = comp.term[index];
            } else {
                subject = comp.term[index];
                predicate = ImageExt.make((ImageExt) comp, inh.getSubject(), index);
            }
        } else if ((comp instanceof ImageInt) && (side == 0)) {
            if (index == ((ImageInt) comp).relationIndex) {
                subject = comp.term[index];
                predicate = Product.make(comp, inh.getPredicate(), index);
            } else {
                subject = ImageInt.make((ImageInt) comp, inh.getPredicate(), index);
                predicate = comp.term[index];
            }
        } else {
            return;
        }
        
        CompoundTerm newInh = null;
        if(predicate.equals(Term.SEQ_SPATIAL)) {
            newInh = (CompoundTerm) Conjunction.make(((CompoundTerm) subject).term, TemporalRules.ORDER_FORWARD, true);
        } else
        if(predicate.equals(Term.SEQ_TEMPORAL)) {
            newInh = (CompoundTerm) Conjunction.make(((CompoundTerm) subject).term, TemporalRules.ORDER_FORWARD, false);
        }
        else 
        {
            newInh = Inheritance.make(subject, predicate);
        }
        if (newInh == null)
            return;
        
        CompoundTerm content = null;
        if (indices.length == 2) {
            content = newInh;
        } else if ((oldContent instanceof Statement) && (indices[0] == 1)) {
            content = Statement.make((Statement) oldContent, oldContent.term[0], newInh, oldContent.getTemporalOrder());
        } else {
            final Term[] componentList;
            final Term condition = oldContent.term[0];
            if (((oldContent instanceof Implication) || (oldContent instanceof Equivalence)) && (condition instanceof Conjunction)) {
                componentList = ((CompoundTerm) condition).cloneTerms();
                componentList[indices[1]] = newInh;
                final Term newCond = Terms.term((CompoundTerm) condition, componentList);
                content = Statement.make((Statement) oldContent, newCond, ((Statement) oldContent).getPredicate(), oldContent.getTemporalOrder());
            } else {
                componentList = oldContent.cloneTerms();
                componentList[indices[0]] = newInh;
                if (oldContent instanceof Conjunction) {
                    final Term newContent = Terms.term(oldContent, componentList);
                    if (!(newContent instanceof CompoundTerm))
                        return;
                    content = (CompoundTerm)newContent;
                } else if ((oldContent instanceof Implication) || (oldContent instanceof Equivalence)) {
                    content = Statement.make((Statement) oldContent, componentList[0], componentList[1], oldContent.getTemporalOrder());
                }
            }
        }
        
        if (content == null)
            return;
        
        final Sentence sentence = nal.getCurrentTask().sentence;
        final TruthValue truth = sentence.truth;
        final BudgetValue budget;
        if (sentence.isQuestion() || sentence.isQuest()) {
            budget = BudgetFunctions.compoundBackward(content, nal);
        } else {
            budget = BudgetFunctions.compoundForward(truth, content, nal);
        }
        
        nal.singlePremiseTask(content, truth, budget);
    }

    /**
     * Equivalent transformation between products and images when the subject is
     * a compound {<(*, S, M) --> P>, S@(*, S, M)} |- <S --> (/, P, _, M)> {<S
     * --> (/, P, _, M)>, P@(/, P, _, M)} |- <(*, S, M) --> P> {<S --> (/, P, _,
     * M)>, M@(/, P, _, M)} |- <M --> (/, P, S, _)>
     *
     * @param subject The subject term
     * @param predicate The predicate term
     * @param nal Reference to the memory
     */
    private static void transformSubjectPI(final short index, final CompoundTerm subject, final Term predicate, final DerivationContext nal) {
        final TruthValue truth = nal.getCurrentTask().sentence.truth;
        BudgetValue budget;
        Inheritance inheritance;
        Term newSubj, newPred;
        if (subject instanceof Product) {
            final Product product = (Product) subject;
            final short i = index;
            if( product.term.length >= i + 1) {
                newSubj = product.term[i];
                newPred = ImageExt.make(product, predicate, i);
                if (!(newSubj instanceof Interval)) { //no intervals as subjects
                    inheritance = Inheritance.make(newSubj, newPred);
                    if (inheritance != null) {
                        if (truth == null) {
                            budget = BudgetFunctions.compoundBackward(inheritance, nal);
                        } else {
                            budget = BudgetFunctions.compoundForward(truth, inheritance, nal);
                        }
                        nal.singlePremiseTask(inheritance, truth, budget);
                    }
                }
            }
        } else if (subject instanceof ImageInt) {
            final ImageInt image = (ImageInt) subject;
            final int relationIndex = image.relationIndex;
            for (short i = 0; i < image.size(); i++) {
                if (i == relationIndex) {
                    newSubj = image.term[relationIndex];
                    newPred = Product.make(image, predicate, relationIndex);
                } else {
                    newSubj = ImageInt.make(image, predicate, i);
                    newPred = image.term[i];
                }
                
                inheritance = Inheritance.make(newSubj, newPred);
                if (inheritance != null) {
                    if (truth == null) {
                        budget = BudgetFunctions.compoundBackward(inheritance, nal);
                    } else {
                        budget = BudgetFunctions.compoundForward(truth, inheritance, nal);
                    }
                    nal.singlePremiseTask(inheritance, truth, budget);
                }
            }
        }
    }

    /**
     * Equivalent transformation between products and images when the predicate
     * is a compound {<(*, S, M) --> P>, S@(*, S, M)} |- <S --> (/, P, _, M)>
     * {<S --> (/, P, _, M)>, P@(/, P, _, M)} |- <(*, S, M) --> P> {<S --> (/,
     * P, _, M)>, M@(/, P, _, M)} |- <M --> (/, P, S, _)>
     *
     * @param subject The subject term
     * @param predicate The predicate term
     * @param nal Reference to the memory
     */
    private static void transformPredicatePI(final short index, final Term subject, final CompoundTerm predicate, final DerivationContext nal) {
        final TruthValue truth = nal.getCurrentTask().sentence.truth;
        BudgetValue budget;
        Inheritance inheritance;
        Term newSubj, newPred;
        if (predicate instanceof Product) {
            final Product product = (Product) predicate;
            final short i = index;
            if (product.term.length >= i+1) {
                newSubj = ImageInt.make(product, subject, i);
                newPred = product.term[i];
                inheritance = Inheritance.make(newSubj, newPred);
                if (inheritance != null) {
                    if (truth == null) {
                        budget = BudgetFunctions.compoundBackward(inheritance, nal);
                    } else {
                        budget = BudgetFunctions.compoundForward(truth, inheritance, nal);
                    }
                    nal.singlePremiseTask(inheritance, truth, budget);
                }
            }
        } else if (predicate instanceof ImageExt) {
            final ImageExt image = (ImageExt) predicate;
            final int relationIndex = image.relationIndex;
            for (short i = 0; i < image.size(); i++) {
                if (i == relationIndex) {
                    newSubj = Product.make(image, subject, relationIndex);
                    newPred = image.term[relationIndex];
                } else {
                    newSubj = image.term[i];
                    newPred = ImageExt.make(image, subject, i);
                }
                
                if(newSubj instanceof CompoundTerm && 
                        (newPred.equals(Term.SEQ_TEMPORAL) || newPred.equals(Term.SEQ_SPATIAL))) {
                    final Term seq = Conjunction.make(((CompoundTerm)newSubj).term,
                                                TemporalRules.ORDER_FORWARD, 
                                                newPred.equals(Term.SEQ_SPATIAL));
                    if (truth == null) {
                        budget = BudgetFunctions.compoundBackward(seq, nal);
                    } else {
                        budget = BudgetFunctions.compoundForward(truth, seq, nal);
                    }
                    nal.singlePremiseTask(seq, truth, budget);
                    return;
                }
                
                inheritance = Inheritance.make(newSubj, newPred);
                if (inheritance != null) { // jmv <<<<<
                    if (truth == null) {
                        budget = BudgetFunctions.compoundBackward(inheritance, nal);
                    } else {
                        budget = BudgetFunctions.compoundForward(truth, inheritance, nal);
                    }
                    nal.singlePremiseTask(inheritance, truth, budget);
                }
            }
        }
    }

    /* --------------- Flatten sequence transform --------------- */
    /**
     * {(#,(#,A,B),C), (#,A,B)@(#,(#,A,B), C)} |- (#,A,B,C)
     * (same for &/)
     * @param compound The premise
     * @param component The recognized component in the premise
     * @param compoundTask Whether the compound comes from the task
     * @param nal Reference to the memory
     */
    static void flattenSequence(final CompoundTerm compound, final Term component, final boolean compoundTask, final int index, final DerivationContext nal) {
        if(compound instanceof Conjunction && component instanceof Conjunction) {
            final Conjunction conjCompound = (Conjunction) compound;
            final Conjunction conjComponent = (Conjunction) component;
            if(conjCompound.getTemporalOrder() == TemporalRules.ORDER_FORWARD && //since parallel conjunction and normal one already is flattened
                    conjComponent.getTemporalOrder() == TemporalRules.ORDER_FORWARD &&
                    conjCompound.getIsSpatial() == conjComponent.getIsSpatial()) { //because also when both are tmporal
                final Term[] newTerm = new Term[conjCompound.size() - 1 + conjComponent.size()];
                System.arraycopy(conjCompound.term, 0, newTerm, 0, index);
                System.arraycopy(conjComponent.term, 0, newTerm, index + 0, conjComponent.size());
                System.arraycopy(conjCompound.term, index + conjComponent.size() - conjComponent.size() + 1, newTerm, index + conjComponent.size(), newTerm.length - (index + conjComponent.size()));
                final Conjunction cont = (Conjunction) Conjunction.make(newTerm, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
                final TruthValue truth = nal.getCurrentTask().sentence.truth.clone();
                final BudgetValue budget = BudgetFunctions.forward(truth, nal);
                nal.singlePremiseTask(cont, truth, budget);
            }
        }
    }
    
    /* --------------- Take out from conjunction --------------- */
    /**
     * {(&&,A,B,C), B@(&&,A,B,C)} |- (&&,A,C)
     * Works for all conjunctions
     * @param compound The premise
     * @param component The recognized component in the premise
     * @param compoundTask Whether the compound comes from the task
     * @param nal Reference to the memory
     */
    static void takeOutFromConjunction(final CompoundTerm compound, final Term component, final boolean compoundTask, final int index, final DerivationContext nal) {
        if(compound instanceof Conjunction) {
            final Conjunction conjCompound = (Conjunction) compound;
            final Term[] newTerm = new Term[conjCompound.size() - 1];
            System.arraycopy(conjCompound.term, 0, newTerm, 0, index);
            System.arraycopy(conjCompound.term, index + 1, newTerm, index, newTerm.length - index);
            final Term cont = Conjunction.make(newTerm, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
            final TruthValue truth = TruthFunctions.deduction(nal.getCurrentTask().sentence.truth, Parameters.reliance);
            final BudgetValue budget = BudgetFunctions.forward(truth, nal);
            nal.singlePremiseTask(cont, truth, budget);
        }
    }
    
    /* --------------- Split sequence apart --------------- */
    /**
     * {(#,A,B,C,D,E), C@(#,A,B,C,D,E)} |- (#,A,B,C), (#,C,D,E)
     * Works for all conjunctions
     * @param compound The premise
     * @param component The recognized component in the premise
     * @param compoundTask Whether the compound comes from the task
     * @param nal Reference to the memory
     */
    static void splitConjunctionApart(final CompoundTerm compound, final Term component, final boolean compoundTask, final int index, final DerivationContext nal) {
        if(compound instanceof Conjunction) {
            final Conjunction conjCompound = (Conjunction) compound;
            final Term[] newTermLeft = new Term[index+1];
            final Term[] newTermRight = new Term[conjCompound.size()-index];
            if(newTermLeft.length == compound.size() || //since nothing was splitted
               newTermRight.length == compound.size()) {
                return;
            }
            System.arraycopy(conjCompound.term, 0, newTermLeft, 0, newTermLeft.length);
            System.arraycopy(conjCompound.term, 0 + index, newTermRight, 0, newTermRight.length);
            final Conjunction cont1 = (Conjunction) Conjunction.make(newTermLeft, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
            final Conjunction cont2 = (Conjunction) Conjunction.make(newTermRight, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
            final TruthValue truth = TruthFunctions.deduction(nal.getCurrentTask().sentence.truth, Parameters.reliance);
            final BudgetValue budget = BudgetFunctions.forward(truth, nal);
            nal.singlePremiseTask(cont1, truth,         budget);
            nal.singlePremiseTask(cont2, truth.clone(), budget.clone());
        }
    }
    
    /* --------------- Group sequence left and right --------------- */
    /**
     * {(#,A,B,C,D,E), C@(#,A,B,C,D,E)} |- (#,(#,A,B),C,D,E), (#,A,B,C,(#,D,E))
     * Works for all conjunctions
     * @param compound The premise
     * @param component The recognized component in the premise
     * @param compoundTask Whether the compound comes from the task
     * @param nal Reference to the memory
     */
    static void groupSequence(final CompoundTerm compound, final Term component, final boolean compoundTask, final int index, final DerivationContext nal) {
        if(!(compound instanceof Conjunction)) {
            return;
        }

        final Conjunction conjCompound = (Conjunction) compound;
        if(conjCompound.getTemporalOrder() != TemporalRules.ORDER_FORWARD) {
            return;
        }

        final boolean hasLeft = index > 1;
        final boolean hasRight = index < compound.size() - 2;
        if(hasLeft) {
            final int minIndex = Memory.randomNumber.nextInt(index-1); //if index-1 it would have length 1, no group
            final Term[] newTermLeft = new Term[(index-minIndex)];
            System.arraycopy(conjCompound.term, minIndex, newTermLeft, minIndex - minIndex, index - minIndex);
            final Term contLeft  = Conjunction.make(newTermLeft,  conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
            final Term[] totalLeft =  new Term[conjCompound.size() - newTermLeft.length + 1];
            //1. add left of min index
            int k=0;
            for(int i=0;i<minIndex;i++) {
                totalLeft[k++] = conjCompound.term[i];
            }
            //add formed group
            totalLeft[k] = contLeft;
            k+=newTermLeft.length-1;
            //and add what is after
            for(int i=index; i<conjCompound.size(); i++) {
                totalLeft[k++] = conjCompound.term[i];
            }
            createSequenceTask(nal, conjCompound, totalLeft);
        }

        if(hasRight) {
            final int maxIndex = compound.term.length - 1 - (Memory.randomNumber.nextInt(1 + (compound.term.length - 1) - (index + 2)));
            final Term[] newTermRight = new Term[maxIndex -index];
            System.arraycopy(conjCompound.term, index + 1, newTermRight, index + 1 - (index + 1), maxIndex + 1 - (index + 1));
            final Term contRight = Conjunction.make(newTermRight, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
            final Term[] totalRight = new Term[conjCompound.size() - newTermRight.length + 1];

            //2. add left of index
            int k=0;
            for(int i=0; i<=index; i++) {
                totalRight[k++] = conjCompound.term[i];
            }
            //add formed group
            totalRight[k] = contRight;
            k+=newTermRight.length-1-1;
            //and add what is after
            for(int i=maxIndex+1;i<conjCompound.size();i++) {
                totalRight[k++] = conjCompound.term[i];
            }
            createSequenceTask(nal, conjCompound, totalRight);
        }
    }

    private static void createSequenceTask(DerivationContext nal, Conjunction conjCompound, Term[] total) {
        final Term cont = Conjunction.make(total, conjCompound.getTemporalOrder(), conjCompound.getIsSpatial());
        if(cont instanceof Conjunction && total.length != conjCompound.size()) {
            final TruthValue truth = nal.getCurrentTask().sentence.truth.clone();
            final BudgetValue budget = BudgetFunctions.forward(truth, nal);
            nal.singlePremiseTask(cont, truth, budget);
        }
    }

    public static void seqToImage(final Conjunction conj, final int index, final DerivationContext nal) {
        final int side = 0; //extensional
        final short[] indices = new short[] { (short)side, (short)index };
        final Product subject = Product.make(conj.term);
        Term predicate = Term.SEQ_TEMPORAL;
        if(conj.isSpatial) {
            predicate = Term.SEQ_SPATIAL;
        }
        final Inheritance inh = Inheritance.make(subject, predicate);
        StructuralRules.transformProductImage(inh, inh, indices, nal);
    }
    
    /* --------------- Disjunction and Conjunction transform --------------- */
    /**
     * {(&&, A, B), A@(&&, A, B)} |- A, or answer (&&, A, B)? using A {(||, A,
     * B), A@(||, A, B)} |- A, or answer (||, A, B)? using A
     *
     * @param compound The premise
     * @param component The recognized component in the premise
     * @param compoundTask Whether the compound comes from the task
     * @param nal Reference to the memory
     */
    static boolean structuralCompound(final CompoundTerm compound, final Term component, final boolean compoundTask, final int index, final DerivationContext nal) {
        
        if(compound instanceof Conjunction) {
            if(nal.getCurrentTask().getTerm() == compound) {
                final Conjunction conj = (Conjunction) compound; //only for # for now, will be gradually applied to &/ later
                if(conj.getTemporalOrder() == TemporalRules.ORDER_FORWARD && conj.isSpatial) { //and some also to && &|
                    //flattenSequence(compound, component, compoundTask, index, nal);
                    groupSequence(compound, component, compoundTask, index, nal);
                    //takeOutFromConjunction(compound, component, compoundTask, index, nal);
                    splitConjunctionApart(compound, component, compoundTask, index, nal);
                }
                if(conj.getTemporalOrder() == TemporalRules.ORDER_FORWARD) {
                    seqToImage(conj, index, nal);
                }
            }
        }
        
        if (component.hasVarIndep()) { //moved down here since flattening also works when indep
            return false;
        } //and also for &/ with index > 0
        if ((compound instanceof Conjunction) && !compound.getIsSpatial() && (compound.getTemporalOrder() == TemporalRules.ORDER_FORWARD) && (index != 0)) {
            return false;
        } 
        
        final Term content = compoundTask ? component : compound;
        final Task task = nal.getCurrentTask();

        final Sentence sentence = task.sentence;
        TruthValue truth = sentence.truth;

        final float reliance = Parameters.reliance;

        final BudgetValue budget;
        if (sentence.isQuestion() || sentence.isQuest()) {
            budget = BudgetFunctions.compoundBackward(content, nal);
        } else {  // need to redefine the cases
            
            //[03:24] <patham9> <a --> b>.     (||,<a --> b>,<x --> y>)?    =>    (||,<a --> b>,<x --> y>).
            //[03:25] <patham9> <a --> b>.     (||,<a --> b>,<x --> y>).     => dont derive it  "outputMustNotContain(<x --> y>)"
            //[03:25] <patham9> <a --> b>.     (&&,<a --> b>,<x --> y>)?    =>      dont derive it   "outputMustNotContain( (&&,<a --> b>,<x --> y>))"
            //[03:25] <patham9> <a --> b>.     (&&,<a --> b>,<x --> y>).   =>    <x --> y>
            if ((sentence.isJudgment() || sentence.isGoal()) && 
                ((!compoundTask && compound instanceof Disjunction) ||
                (compoundTask && compound instanceof Conjunction))) {
                truth = TruthFunctions.deduction(truth, reliance);
            }else {
                final TruthValue v1;
                final TruthValue v2;
                v1 = TruthFunctions.negation(truth);
                v2 = TruthFunctions.deduction(v1, reliance);
                truth = TruthFunctions.negation(v2);
            }
            budget = BudgetFunctions.forward(truth, nal);
        }
        return nal.singlePremiseTask(content, truth, budget);
    }

    /* --------------- Negation related rules --------------- */
    /**
     * {A, A@(--, A)} |- (--, A)
     *
     * @param content The premise
     * @param nal Reference to the memory
     */
    public static void transformNegation(final CompoundTerm content, final DerivationContext nal) {
        final Task task = nal.getCurrentTask();
        final Sentence sentence = task.sentence;
        TruthValue truth = sentence.truth;

        final BudgetValue budget;
        
        if (sentence.isJudgment() || sentence.isGoal()) {
            truth = TruthFunctions.negation(truth);
            budget = BudgetFunctions.compoundForward(truth, content, nal);
        } else {
            budget = BudgetFunctions.compoundBackward(content, nal);
        }
        nal.singlePremiseTask(content, truth, budget);
    }

    /**
     * {<A ==> B>, A@(--, A)} |- <(--, B) ==> (--, A)>
     *
     * @param statement The premise
     * @param nal Reference to the memory
     */
    protected static boolean contraposition(final Statement statement, final Sentence sentence, final DerivationContext nal) {
        final Memory memory = nal.mem();
        //memory.logic.CONTRAPOSITION.commit(statement.complexity);
        
        final Term subj = statement.getSubject();
        final Term pred = statement.getPredicate();
        
        final Statement content = Statement.make(statement,
                Negation.make(pred), 
                Negation.make(subj), 
                TemporalRules.reverseOrder(statement.getTemporalOrder()));                
        
        if (content == null) return false;
        
        TruthValue truth = sentence.truth;
        final BudgetValue budget;
        if (sentence.isQuestion() || sentence.isQuest()) {
            if (content instanceof Implication) {
                budget = BudgetFunctions.compoundBackwardWeak(content, nal);
            } else {
                budget = BudgetFunctions.compoundBackward(content, nal);
            }
            return nal.singlePremiseTask(content, Symbols.QUESTION_MARK, truth, budget);
        } else {
            if (content instanceof Implication) {
                truth = TruthFunctions.contraposition(truth);
            }
            budget = BudgetFunctions.compoundForward(truth, content, nal);
            return nal.singlePremiseTask(content, Symbols.JUDGMENT_MARK, truth, budget);
        }
    }
}
