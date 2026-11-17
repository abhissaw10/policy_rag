package com.example.policyrag.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;

@Route("ask")
@PageTitle("Ask HR")
@JsModule("./chat-helper.js")
public class ChatView extends VerticalLayout {
    private final TextField employeeId = new TextField("Employee ID");
    private final TextArea question = new TextArea("Your question");
    private final Div messages = new Div();
    private final Div bridge = new Div();
    private final Span status = new Span("Answers come from the shared HR policy library.");
    private final Button send = new Button("Send question", VaadinIcon.ARROW_RIGHT.create());

    public ChatView() {
        addClassName("policy-page");
        addClassName("chat-page");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        Div card = new Div();
        card.addClassNames("upload-card", "chat-card");
        HorizontalLayout navigation = new HorizontalLayout(new RouterLink("Upload a policy", MainView.class),
                new RouterLink("Ask HR", ChatView.class));
        navigation.addClassName("policy-nav");
        Span eyebrow = new Span("PEOPLE OPERATIONS");
        eyebrow.addClassName("eyebrow");
        H1 heading = new H1("A little clarity from HR.");
        Paragraph description = new Paragraph("Ask one specific policy question. Get the source paragraph and its PDF name.");
        description.addClassName("description");
        employeeId.setMaxLength(5);
        employeeId.setPlaceholder("e.g. E1234");
        employeeId.setRequiredIndicatorVisible(true);
        employeeId.setHelperText("Your employee ID. You can search policies uploaded by any employee.");
        employeeId.setWidthFull();
        String uploadedEmployeeId = (String) VaadinSession.getCurrent().getAttribute("policy.uploadEmployeeId");
        if (uploadedEmployeeId != null) employeeId.setValue(uploadedEmployeeId);
        employeeId.addValueChangeListener(event -> {
            messages.removeAll();
            welcome();
        });
        messages.addClassName("chat-messages");
        messages.getElement().setAttribute("role", "log");
        messages.getElement().setAttribute("aria-live", "polite");
        welcome();
        question.setMaxLength(100);
        question.setWidthFull();
        question.setPlaceholder("How many annual leave days do I receive?");
        question.setMinRows(2);
        question.setMaxRows(3);
        question.setValueChangeMode(ValueChangeMode.EAGER);
        question.setHelperText("0 / 100 characters");
        question.addValueChangeListener(event -> question.setHelperText(event.getValue().length() + " / 100 characters"));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(event -> send());
        Button clear = new Button("Clear conversation", event -> { messages.removeAll(); welcome(); });
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout actions = new HorizontalLayout(clear, send);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.BETWEEN);
        status.addClassName("chat-status");
        bridge.addClassName("upload-bridge");
        bridge.getElement().addEventListener("chat-response", event -> {
            send.setEnabled(true);
            employeeId.setEnabled(true);
            question.setEnabled(true);
            String message = event.getEventData().get("event.detail.message").asString("The request could not be completed.");
            boolean cached = event.getEventData().get("event.detail.cached").asBoolean(false);
            bubble("Policy assistant", message, false);
            status.setText(cached ? "Answered from the policy response cache." : "Answers come from the shared HR policy library.");
            question.focus();
        }).addEventData("event.detail.message").addEventData("event.detail.cached");
        card.add(navigation, eyebrow, heading, description, employeeId, messages, question, actions, status, bridge);
        add(card);
    }

    private void send() {
        String id = employeeId.getValue().strip();
        String prompt = question.getValue();
        if (id.isBlank() || id.length() > 5) {
            employeeId.setInvalid(true);
            employeeId.setErrorMessage("Enter an employee ID of up to 5 characters.");
            return;
        }
        employeeId.setInvalid(false);
        if (prompt.isBlank() || prompt.codePointCount(0, prompt.length()) > 100) {
            question.setInvalid(true);
            question.setErrorMessage("Ask a question of up to 100 characters.");
            return;
        }
        question.setInvalid(false);
        bubble("You", prompt, true);
        question.clear();
        send.setEnabled(false);
        employeeId.setEnabled(false);
        question.setEnabled(false);
        status.setText("Searching the shared HR policies…");
        bridge.getElement().executeJs("window.policyChat.send(this, $0, $1)", id, prompt);
    }

    private void welcome() {
        bubble("Policy assistant", "What would you like to know about your HR policies? Keep your question specific.", false);
    }

    private void bubble(String speaker, String message, boolean user) {
        Div bubble = new Div();
        bubble.addClassNames("chat-bubble", user ? "chat-user" : "chat-assistant");
        Span label = new Span(speaker);
        label.addClassName("chat-speaker");
        Paragraph text = new Paragraph(message);
        bubble.add(label, text);
        messages.add(bubble);
        bubble.getElement().executeJs("if (this.parentElement) this.parentElement.scrollTop = this.parentElement.scrollHeight");
    }
}
