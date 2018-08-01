package com.cienet.deliverydemo.token;

import co.paralleluniverse.fibers.Suspendable;
import com.cienet.deliverydemo.order.OrderPlaceFlow;
import com.cienet.deliverydemo.order.OrderState;
import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.CommandData;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.TransactionState;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;

import java.security.PublicKey;
import java.util.List;
import java.util.stream.Collectors;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class TokenIssueFlow {
    /* Our flow, automating the process of updating the ledger.
     * See src/main/java/examples/IAmAFlowPair.java for an example. */
    @InitiatingFlow
    @StartableByRPC
    public static class Request extends FlowLogic<SignedTransaction> {
        private final Party owner;
        private final int amount;

        private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction.");
        private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
        private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
        private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        );

        public Request(Party owner, int amount) {
            this.owner = owner;
            this.amount = amount;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // We choose our transaction's notary (the notary prevents double-spends).
            Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
            // We get a reference to our own identity.
            Party issuer = getOurIdentity();

            TokenState tokenState = new TokenState(issuer, owner, amount);

            progressTracker.setCurrentStep(GENERATING_TRANSACTION);
            TransactionBuilder transactionBuilder = new TransactionBuilder(notary);

            transactionBuilder.addOutputState(tokenState, TokenContract.ID, notary);

            CommandData commandData = new TokenContract.Issue();
            //        List<PublicKey> requiredSigners = ImmutableList.of(issuer.getOwningKey());
            //        Command command = new Command<>(commandData, requiredSigners);
            //        transactionBuilder.addCommand(command);
            transactionBuilder.addCommand(commandData, issuer.getOwningKey(), owner.getOwningKey());

            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            transactionBuilder.verify(getServiceHub());

            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(transactionBuilder);

            progressTracker.setCurrentStep(GATHERING_SIGS);
            List<FlowSession> otherPartySession =
                    ImmutableList.of(initiateFlow(owner));
            final SignedTransaction fullySignedTx = subFlow(
                    new CollectSignaturesFlow(
                            partSignedTx,
                            otherPartySession,
                            CollectSignaturesFlow.Companion.tracker()));

            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            return subFlow(new FinalityFlow(fullySignedTx));
        }
    }

    @InitiatedBy(Request.class)
    public static class Confirm extends FlowLogic<SignedTransaction> {

        private final FlowSession otherPartyFlow;

        public Confirm(FlowSession otherPartyFlow) {
            this.otherPartyFlow = otherPartyFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            class SignTxFlow extends SignTransactionFlow {
                private SignTxFlow(FlowSession otherPartyFlow, ProgressTracker progressTracker) {
                    super(otherPartyFlow, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {

                }
            }

            return subFlow(new SignTxFlow(otherPartyFlow, SignTransactionFlow.Companion.tracker()));
        }
    }
}