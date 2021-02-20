package com.structurizr.dsl;

import com.structurizr.Workspace;
import com.structurizr.model.*;
import com.structurizr.util.StringUtils;
import com.structurizr.view.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main DSL parser class - forms the API for using the parser.
 */
public final class StructurizrDslParser extends StructurizrDslTokens {

    private static final Pattern EMPTY_LINE_PATTERN = Pattern.compile("^\\s*");
    private static final Pattern TOKENS_PATTERN = Pattern.compile("\"((\\\\.|[^\"])*)\"|(\\S+)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\w+");

    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*?(//|#).*$");
    private static final String MULTI_LINE_COMMENT_START_TOKEN = "/*";
    private static final String MULTI_LINE_COMMENT_END_TOKEN = "*/";

    private static final Pattern STRING_SUBSTITUTION_PATTERN = Pattern.compile("(\\$\\{[a-zA-Z0-9-_.]+?})");

    private Stack<DslContext> contextStack;
    private Map<String, Element> elements;
    private Map<String, Relationship> relationships;
    private Map<String, Constant> constants;

    private List<String> dslSourceLines = new ArrayList<>();
    private Workspace workspace;

    private boolean restricted = false;

    /**
     * Creates a new instance of the parser.
     */
    public StructurizrDslParser() {
        contextStack = new Stack<>();
        elements = new HashMap<>();
        relationships = new HashMap<>();
        constants = new HashMap<>();

        workspace = new Workspace("Name", "Description");
        workspace.getModel().setImpliedRelationshipsStrategy(new CreateImpliedRelationshipsUnlessAnyRelationshipExistsStrategy());
    }

    /**
     * Sets whether to run this parser in restricted mode (this stops !include, !docs, !adrs from working).
     *
     * @param restricted        true for restricted mode, false otherwise
     */
    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    /**
     * Gets the workspace that has been created by parsing the Structurizr DSL.
     *
     * @return  a Workspace instance
     */
    public Workspace getWorkspace() {
        DslUtils.setDsl(workspace, getParsedDsl());

        return workspace;
    }

    private String getParsedDsl() {
        StringBuilder buf = new StringBuilder();

        for (String line : dslSourceLines) {
            buf.append(line);
            buf.append(System.lineSeparator());
        }

        return buf.toString();
    }

    /**
     * Parses the specified Structurizr DSL file(s), adding the parsed content to the workspace.
     * If "path" represents a single file, that single file will be parsed.
     * If "path" represents a directory, all files in that directory (recursively) will be parsed.
     *
     * @param path      a File object representing a file or directory
     */
    public void parse(File path) throws StructurizrDslParserException {
        if (path == null) {
            throw new RuntimeException("A file must be specified");
        }

        if (!path.exists()) {
            throw new RuntimeException("The file at " + path.getAbsolutePath() + " does not exist");
        }

        List<File> files = FileUtils.findFiles(path);
        try {
            for (File file : files) {
                parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8), file);
            }
        } catch (IOException e) {
            throw new StructurizrDslParserException(e.getMessage());
        }
    }

    /**
     * Parses the specified Structurizr DSL fragment, adding the parsed content to the workspace.
     *
     * @param dsl       a DSL fragment
     */
    public void parse(String dsl) throws StructurizrDslParserException {
        if (StringUtils.isNullOrEmpty(dsl)) {
            throw new RuntimeException("A DSL fragment must be specified");
        }

        List<String> lines = Arrays.asList(dsl.split("\\r?\\n"));
        parse(lines, new File("."));
    }

    void parse(List<String> lines, File file) throws StructurizrDslParserException {
        int lineNumber = 1;
        for (String line : lines) {
            boolean includeInDslSourceLines = true;

            try {
                if (EMPTY_LINE_PATTERN.matcher(line).matches()) {
                    // do nothing
                } else if (COMMENT_PATTERN.matcher(line).matches()) {
                    // do nothing
                } else {
                    List<String> listOfTokens = new ArrayList<>();
                    Matcher m = TOKENS_PATTERN.matcher(line.trim());
                    while (m.find()) {
                        String token = null;
                        if (m.group(1) != null) {
                            // this is a token specified between double-quotes
                            token = m.group(1);
                        } else {
                            // this is a token specified without double-quotes
                            token = m.group(3);
                        }

                        if (token != null) {
                            token = substituteStrings(token);
                            listOfTokens.add(token);
                        }
                    }

                    Tokens tokens = new Tokens(listOfTokens);

                    String identifier = null;
                    if (tokens.size() > 3 && ASSIGNMENT_OPERATOR_TOKEN.equals(tokens.get(1))) {
                        identifier = tokens.get(0).toLowerCase();
                        validateIdentifier(identifier);

                        tokens = new Tokens(listOfTokens.subList(2, listOfTokens.size()));
                    }

                    String firstToken = tokens.get(0);

                    if (line.trim().startsWith(MULTI_LINE_COMMENT_START_TOKEN) && line.trim().endsWith(MULTI_LINE_COMMENT_END_TOKEN)) {
                        // do nothing
                    } else if (firstToken.startsWith(MULTI_LINE_COMMENT_START_TOKEN)) {
                        startContext(new CommentDslContext());

                    } else if (inContext(CommentDslContext.class) && line.trim().endsWith(MULTI_LINE_COMMENT_END_TOKEN)) {
                        endContext();

                    } else if (inContext(CommentDslContext.class)) {
                        // do nothing

                    } else if (DslContext.CONTEXT_END_TOKEN.equals(tokens.get(0))) {
                        endContext();

                    } else if (tokens.size() > 2 && RELATIONSHIP_TOKEN.equals(tokens.get(1)) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class) || inContext(PersonDslContext.class) || inContext(SoftwareSystemDslContext.class) || inContext(ContainerDslContext.class) || inContext(ComponentDslContext.class) || inContext(DeploymentEnvironmentDslContext.class) || inContext(DeploymentNodeDslContext.class) || inContext(InfrastructureNodeDslContext.class) || inContext(SoftwareSystemInstanceDslContext.class) || inContext(ContainerInstanceDslContext.class))) {
                        Relationship relationship = new ExplicitRelationshipParser().parse(getContext(), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new RelationshipDslContext(relationship));
                        }

                        if (identifier != null) {
                            relationships.put(identifier, relationship);
                        }

                    } else if (tokens.size() >= 2 && RELATIONSHIP_TOKEN.equals(tokens.get(0)) && (inContext(PersonDslContext.class) || inContext(SoftwareSystemDslContext.class) || inContext(ContainerDslContext.class) || inContext(ComponentDslContext.class) || inContext(DeploymentNodeDslContext.class) || inContext(InfrastructureNodeDslContext.class) || inContext(SoftwareSystemInstanceDslContext.class) || inContext(ContainerInstanceDslContext.class))) {
                        Relationship relationship = new ImplicitRelationshipParser().parse(getContext(ModelItemDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new RelationshipDslContext(relationship));
                        }

                        if (identifier != null) {
                            relationships.put(identifier, relationship);
                        }

                    } else if (PERSON_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class))) {
                        Person person = new PersonParser().parse(getContext(GroupableDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new PersonDslContext(person));
                        }

                        if (identifier != null) {
                            elements.put(identifier, person);
                        }

                    } else if (SOFTWARE_SYSTEM_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class))) {
                        SoftwareSystem softwareSystem = new SoftwareSystemParser().parse(getContext(GroupableDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new SoftwareSystemDslContext(softwareSystem));
                        }

                        if (identifier != null) {
                            elements.put(identifier, softwareSystem);
                        }

                    } else if (CONTAINER_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        Container container = new ContainerParser().parse(getContext(SoftwareSystemDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ContainerDslContext(container));
                        }

                        if (identifier != null) {
                            elements.put(identifier, container);
                        }

                    } else if (COMPONENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ContainerDslContext.class)) {
                        Component component = new ComponentParser().parse(getContext(ContainerDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ComponentDslContext(component));
                        }

                        if (identifier != null) {
                            elements.put(identifier, component);
                        }

                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class) && !getContext(ModelDslContext.class).hasGroup()) {
                        String group = new GroupParser().parse(tokens.withoutContextStartToken());

                        startContext(new ModelDslContext(group));
                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(EnterpriseDslContext.class) && !getContext(EnterpriseDslContext.class).hasGroup()) {
                        String group = new GroupParser().parse(tokens.withoutContextStartToken());

                        startContext(new EnterpriseDslContext(group));
                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class) && !getContext(SoftwareSystemDslContext.class).hasGroup()) {
                        String group = new GroupParser().parse(tokens.withoutContextStartToken());

                        SoftwareSystem softwareSystem = getContext(SoftwareSystemDslContext.class).getSoftwareSystem();
                        startContext(new SoftwareSystemDslContext(softwareSystem, group));
                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(ContainerDslContext.class) && !getContext(ContainerDslContext.class).hasGroup()) {
                        String group = new GroupParser().parse(tokens.withoutContextStartToken());

                        Container container = getContext(ContainerDslContext.class).getContainer();
                        startContext(new ContainerDslContext(container, group));
                    } else if (URL_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        new ModelItemParser().parseUrl(getContext(ModelItemDslContext.class), tokens);

                    } else if (PROPERTIES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        startContext(new ModelItemPropertiesDslContext(getContext(ModelItemDslContext.class).getModelItem()));

                    } else if (inContext(ModelItemPropertiesDslContext.class)) {
                        new ModelItemParser().parseProperty(getContext(ModelItemPropertiesDslContext.class), tokens);

                    } else if (PERSPECTIVES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        startContext(new ModelItemPerspectivesDslContext(getContext(ModelItemDslContext.class).getModelItem()));

                    } else if (inContext(ModelItemPerspectivesDslContext.class)) {
                        new ModelItemParser().parsePerspective(getContext(ModelItemPerspectivesDslContext.class), tokens);

                    } else if (WORKSPACE_TOKEN.equalsIgnoreCase(firstToken) && contextStack.empty()) {
                        new WorkspaceParser().parse(workspace, tokens.withoutContextStartToken());
                        startContext(new WorkspaceDslContext());

                    } else if (IMPLIED_RELATIONSHIPS_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class)) {
                        new ImpliedRelationshipsParser().parse(getContext(), tokens);

                    } else if (MODEL_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ModelDslContext());

                    } else if (VIEWS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ViewsDslContext());

                    } else if (BRANDING_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new BrandingDslContext(file));

                    } else if (BRANDING_LOGO_TOKEN.equalsIgnoreCase(firstToken) && inContext(BrandingDslContext.class)) {
                        if (!restricted) {
                            new BrandingParser().parseLogo(getContext(BrandingDslContext.class), tokens);
                        }

                    } else if (BRANDING_FONT_TOKEN.equalsIgnoreCase(firstToken) && inContext(BrandingDslContext.class)) {
                        new BrandingParser().parseFont(getContext(BrandingDslContext.class), tokens);

                    } else if (STYLES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new StylesDslContext());

                    } else if (ELEMENT_STYLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StylesDslContext.class)) {
                        ElementStyle elementStyle = new ElementStyleParser().parseElementStyle(getContext(), tokens.withoutContextStartToken());
                        startContext(new ElementStyleDslContext(elementStyle, file));

                    } else if (ELEMENT_STYLE_BACKGROUND_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseBackground(getContext(ElementStyleDslContext.class), tokens);

                    } else if ((ELEMENT_STYLE_COLOUR_TOKEN.equalsIgnoreCase(firstToken) || ELEMENT_STYLE_COLOR_TOKEN.equalsIgnoreCase(firstToken)) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseColour(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_STROKE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseStroke(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_SHAPE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseShape(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_BORDER_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseBorder(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_OPACITY_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseOpacity(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_WIDTH_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseWidth(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_HEIGHT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseHeight(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_FONT_SIZE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseFontSize(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_METADATA_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseMetadata(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_DESCRIPTION_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseDescription(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_ICON_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        if (!restricted) {
                            new ElementStyleParser().parseIcon(getContext(ElementStyleDslContext.class), tokens);
                        }

                    } else if (RELATIONSHIP_STYLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StylesDslContext.class)) {
                        RelationshipStyle relationshipStyle = new RelationshipStyleParser().parseRelationshipStyle(getContext(), tokens.withoutContextStartToken());
                        startContext(new RelationshipStyleDslContext(relationshipStyle));

                    } else if (RELATIONSHIP_STYLE_THICKNESS_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseThickness(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if ((RELATIONSHIP_STYLE_COLOUR_TOKEN.equalsIgnoreCase(firstToken) || RELATIONSHIP_STYLE_COLOR_TOKEN.equalsIgnoreCase(firstToken)) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseColour(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_DASHED_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseDashed(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_OPACITY_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseOpacity(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_WIDTH_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseWidth(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_FONT_SIZE_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseFontSize(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_POSITION_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parsePosition(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_ROUTING_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseRouting(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (ENTERPRISE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class)) {
                        new EnterpriseParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new EnterpriseDslContext());

                    } else if (DEPLOYMENT_ENVIRONMENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class)) {
                        String environment = new DeploymentEnvironmentParser().parse(tokens.withoutContextStartToken());
                        startContext(new DeploymentEnvironmentDslContext(environment));

                        if (identifier != null) {
                            DeploymentEnvironment deploymentEnvironment = new DeploymentEnvironment(environment);
                            elements.put(identifier, deploymentEnvironment);
                        }

                    } else if (DEPLOYMENT_NODE_TOKEN.equalsIgnoreCase(firstToken) && (inContext(DeploymentEnvironmentDslContext.class) || inContext(DeploymentNodeDslContext.class))) {
                        DeploymentNode deploymentNode = new DeploymentNodeParser().parse(getContext(), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new DeploymentNodeDslContext(deploymentNode));
                        }

                        if (identifier != null) {
                            elements.put(identifier, deploymentNode);
                        }
                    } else if (INFRASTRUCTURE_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        InfrastructureNode infrastructureNode = new InfrastructureNodeParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new InfrastructureNodeDslContext(infrastructureNode));
                        }

                        if (identifier != null) {
                            elements.put(identifier, infrastructureNode);
                        }

                    } else if (SOFTWARE_SYSTEM_INSTANCE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        SoftwareSystemInstance softwareSystemInstance = new SoftwareSystemInstanceParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new SoftwareSystemInstanceDslContext(softwareSystemInstance));
                        }

                        if (identifier != null) {
                            elements.put(identifier, softwareSystemInstance);
                        }

                    } else if (CONTAINER_INSTANCE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        ContainerInstance containerInstance = new ContainerInstanceParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ContainerInstanceDslContext(containerInstance));
                        }

                        if (identifier != null) {
                            elements.put(identifier, containerInstance);
                        }

                    } else if (SYSTEM_LANDSCAPE_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        SystemLandscapeView view = new SystemLandscapeViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new SystemLandscapeViewDslContext(view));

                    } else if (SYSTEM_CONTEXT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        SystemContextView view = new SystemContextViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new SystemContextViewDslContext(view));

                    } else if (CONTAINER_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        ContainerView view = new ContainerViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new ContainerViewDslContext(view));

                    } else if (COMPONENT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        ComponentView view = new ComponentViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new ComponentViewDslContext(view));

                    } else if (DYNAMIC_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        DynamicView view = new DynamicViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new DynamicViewDslContext(view));

                    } else if (DEPLOYMENT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        DeploymentView view = new DeploymentViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new DeploymentViewDslContext(view));

                    } else if (FILTERED_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        new FilteredViewParser().parse(getContext(), tokens);

                    } else if (tokens.size() > 2 && RELATIONSHIP_TOKEN.equals(tokens.get(1)) && inContext(DynamicViewDslContext.class)) {
                        new DynamicViewContentParser().parseRelationship(getContext(DynamicViewDslContext.class), tokens);

                    } else if (INCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewContentParser().parseInclude(getContext(StaticViewDslContext.class), tokens);

                    } else if (EXCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewContentParser().parseExclude(getContext(StaticViewDslContext.class), tokens);

                    } else if (ANIMATION_STEP_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewAnimationStepParser().parse(getContext(StaticViewDslContext.class), tokens);

                    } else if (ANIMATION_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        startContext(new StaticViewAnimationDslContext(getContext(StaticViewDslContext.class).getView()));

                    } else if (inContext(StaticViewAnimationDslContext.class)) {
                        new StaticViewAnimationStepParser().parse(getContext(StaticViewAnimationDslContext.class), tokens);

                    } else if (INCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewContentParser().parseInclude(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (EXCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewContentParser().parseExclude(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (ANIMATION_STEP_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewAnimationStepParser().parse(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (ANIMATION_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        startContext(new DeploymentViewAnimationDslContext(getContext(DeploymentViewDslContext.class).getView()));

                    } else if (inContext(DeploymentViewAnimationDslContext.class)) {
                        new DeploymentViewAnimationStepParser().parse(getContext(DeploymentViewAnimationDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(StaticViewDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DynamicViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(DynamicViewDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(StaticViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DynamicViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(DynamicViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (THEMES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        new ThemesParser().parse(getContext(), tokens);

                    } else if (TERMINOLOGY_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new TerminologyDslContext());

                    } else if (ENTERPRISE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseEnterprise(getContext(), tokens);

                    } else if (PERSON_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parsePerson(getContext(), tokens);

                    } else if (SOFTWARE_SYSTEM_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseSoftwareSystem(getContext(), tokens);

                    } else if (CONTAINER_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseContainer(getContext(), tokens);

                    } else if (COMPONENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseComponent(getContext(), tokens);

                    } else if (DEPLOYMENT_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseDeploymentNode(getContext(), tokens);

                    } else if (INFRASTRUCTURE_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseInfrastructureNode(getContext(), tokens);

                    } else if (TERMINOLOGY_RELATIONSHIP_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseRelationship(getContext(), tokens);

                    } else if (CONFIGURATION_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ConfigurationDslContext());

                    } else if (USERS_TOKEN.equalsIgnoreCase(firstToken) && inContext(ConfigurationDslContext.class)) {
                        startContext(new UsersDslContext());

                    } else if (inContext(UsersDslContext.class)) {
                        new UserRoleParser().parse(getContext(), tokens);

                    } else if (INCLUDE_FILE_TOKEN.equalsIgnoreCase(firstToken)) {
                        if (!restricted) {
                            IncludedDslContext context = new IncludedDslContext(file);
                            new IncludeParser().parse(context, tokens);
                            parse(context.getLines(), context.getFile());
                            includeInDslSourceLines = false;
                        }

                    } else if (DOCS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        if (!restricted) {
                            new DocsParser().parse(getContext(WorkspaceDslContext.class), file, tokens);
                        }

                    } else if (DOCS_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        if (!restricted) {
                            new DocsParser().parse(getContext(SoftwareSystemDslContext.class), file, tokens);
                        }

                    } else if (ADRS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        if (!restricted) {
                            new AdrsParser().parse(getContext(WorkspaceDslContext.class), file, tokens);
                        }

                    } else if (ADRS_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        if (!restricted) {
                            new AdrsParser().parse(getContext(SoftwareSystemDslContext.class), file, tokens);
                        }

                    } else if (CONSTANT_TOKEN.equalsIgnoreCase(firstToken)) {
                        Constant constant = new ConstantParser().parse(getContext(), tokens);
                        constants.put(constant.getName(), constant);

                    } else {
                        throw new StructurizrDslParserException("Unexpected tokens");
                    }
                }

                if (includeInDslSourceLines) {
                    dslSourceLines.add(line);
                }

                lineNumber++;
            } catch (Exception e) {
                throw new StructurizrDslParserException(e.getMessage(), lineNumber, line);
            }
        }
    }

    private String substituteStrings(String token) {
        Matcher m = STRING_SUBSTITUTION_PATTERN.matcher(token);
        while (m.find()) {
            String before = m.group(0);
            String after = null;
            String name = before.substring(2, before.length()-1);
            if (constants.containsKey(name)) {
                after = constants.get(name).getValue();
            } else {
                if (!restricted) {
                    String environmentVariable = System.getenv().get(name);
                    if (environmentVariable != null) {
                        after = environmentVariable;
                    }
                }
            }

            if (after != null) {
                token = token.replace(before, after);
            }
        }

        return token;
    }

    private boolean shouldStartContext(Tokens tokens) {
        return DslContext.CONTEXT_START_TOKEN.equalsIgnoreCase(tokens.get(tokens.size()-1));
    }

    private void startContext(DslContext context) {
        context.setWorkspace(workspace);
        context.setElements(elements);
        context.setRelationships(relationships);
        contextStack.push(context);
    }

    private DslContext getContext() {
        if (!contextStack.empty()) {
            return contextStack.peek();
        } else {
            return null;
        }
    }

    private <T> T getContext(Class<T> clazz) {
        if (inContext(clazz)) {
            return (T)contextStack.peek();
        } else {
            throw new RuntimeException("Expected " + clazz.getName() + " but got " + contextStack.peek().getClass().getName());
        }
    }

    private void endContext() {
        if (!contextStack.empty()) {
            contextStack.pop();
        } else {
            throw new RuntimeException("Unexpected end of context");
        }
    }

    private void validateIdentifier(String identifier) {
        if (elements.containsKey(identifier) || relationships.containsKey(identifier)) {
            throw new RuntimeException("The identifier \"" + identifier + "\" is already in use");
        }

        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new RuntimeException("Identifiers can only contain the following characters: a-zA-Z_0-9");
        }
    }

    private boolean inContext(Class clazz) {
        if (contextStack.empty()) {
            return false;
        }

        return clazz.isAssignableFrom(contextStack.peek().getClass());
    }

}