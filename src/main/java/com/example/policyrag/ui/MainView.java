package com.example.policyrag.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;

@Route("")
@PageTitle("HR Policy Upload")
@JsModule("./upload-helper.js")
public class MainView extends VerticalLayout {

    private final TextField employeeId = new TextField("Employee ID");
    private final Div uploadBridge = new Div();
    private final Span selectedFile = new Span("No file selected");
    private final Button uploadButton = new Button("Upload policy", VaadinIcon.CLOUD_UPLOAD.create());

    public MainView() {
        addClassName("policy-page");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);

        Div card = new Div();
        card.addClassName("upload-card");

        Span eyebrow = new Span("PEOPLE OPERATIONS");
        eyebrow.addClassName("eyebrow");
        H1 heading = new H1("Upload an HR policy");
        Paragraph description = new Paragraph(
                "Add a PDF to the policy knowledge base. We verify it, split it into semantic sections, and index it for retrieval.");
        description.addClassName("description");

        employeeId.setPlaceholder("e.g. E1024");
        employeeId.setRequired(true);
        employeeId.setRequiredIndicatorVisible(true);
        employeeId.setMaxLength(5);
        employeeId.setWidthFull();
        employeeId.setHelperText("Maximum 5 characters");

        uploadBridge.addClassName("upload-bridge");
        uploadBridge.getElement().setAttribute("aria-hidden", "true");

        Button browseButton = new Button("Browse PDF", VaadinIcon.FILE_SEARCH.create());
        browseButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        browseButton.addClickListener(event -> uploadBridge.getElement()
                .executeJs("window.policyUpload.open(this)"));

        selectedFile.addClassName("selected-file");
        HorizontalLayout picker = new HorizontalLayout(browseButton, selectedFile);
        picker.addClassName("file-picker");
        picker.setAlignItems(Alignment.CENTER);
        picker.setWidthFull();
        picker.expand(selectedFile);

        Span constraints = new Span("PDF only  •  Maximum 25 KB");
        constraints.addClassName("constraints");

        uploadButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        uploadButton.setWidthFull();
        uploadButton.addClickListener(event -> startUpload());

        uploadBridge.getElement().addEventListener("file-selected", event -> {
            String name = event.getEventData().get("event.detail.name").asString("");
            selectedFile.setText(name == null || name.isBlank() ? "No file selected" : name);
        }).addEventData("event.detail.name");

        uploadBridge.getElement().addEventListener("upload-start", event -> uploadButton.setEnabled(false));
        uploadBridge.getElement().addEventListener("upload-success", event -> {
            uploadButton.setEnabled(true);
            showMessage("File Uploaded Successfully", true);
            VaadinSession.getCurrent().setAttribute("policy.uploadEmployeeId", employeeId.getValue().strip());
            employeeId.clear();
            selectedFile.setText("No file selected");
        });
        uploadBridge.getElement().addEventListener("upload-error", event -> {
            uploadButton.setEnabled(true);
            showMessage(event.getEventData().get("event.detail.message").asString("Upload failed"), false);
        }).addEventData("event.detail.message");

        HorizontalLayout navigation = new HorizontalLayout(new RouterLink("Upload a policy", MainView.class),
                new RouterLink("Ask HR", ChatView.class));
        navigation.addClassName("policy-nav");
        card.add(navigation, eyebrow, heading, description, employeeId, picker, constraints, uploadButton, uploadBridge);
        add(card);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        uploadBridge.getElement().executeJs("window.policyUpload.install(this)");
    }

    private void startUpload() {
        String value = employeeId.getValue() == null ? "" : employeeId.getValue().trim();
        if (value.isBlank()) {
            employeeId.setInvalid(true);
            employeeId.setErrorMessage("Employee ID is required");
            return;
        }
        if (value.length() > 5) {
            employeeId.setInvalid(true);
            employeeId.setErrorMessage("Employee ID must not exceed 5 characters");
            return;
        }
        employeeId.setInvalid(false);
        uploadBridge.getElement().executeJs("window.policyUpload.upload(this, $0)", value);
    }

    private void showMessage(String message, boolean success) {
        Notification notification = Notification.show(
                message == null || message.isBlank() ? "Upload failed" : message,
                5000,
                Notification.Position.TOP_CENTER);
        notification.addThemeVariants(success
                ? NotificationVariant.LUMO_SUCCESS
                : NotificationVariant.LUMO_ERROR);
    }
}
