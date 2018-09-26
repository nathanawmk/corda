package net.corda.docs.java.tutorial.helloworld;

import net.corda.core.contracts.ContractState;
import net.corda.core.identity.AbstractParty;
import java.util.List;

// DOCSTART 01
// Add these imports:
import com.google.common.collect.ImmutableList;
import net.corda.core.identity.Party;

// Replace TemplateState's definition with:
public class IOUState implements ContractState {
    private final int value;
    private final Party lender;

    public IOUState(int value, Party lender) {
        this.value = value;
        this.lender = lender;
    }

    public int getValue() {
        return value;
    }

    public Party getLender() {
        return lender;
    }

    @Override
    public List<AbstractParty> getParticipants() {
        return ImmutableList.of(lender);
    }
}
// DOCEND 01