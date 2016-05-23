package org.powertac.visualizer.web.rest;

import org.powertac.visualizer.Visualizer2App;
import org.powertac.visualizer.domain.File;
import org.powertac.visualizer.repository.FileRepository;
import org.powertac.visualizer.repository.UserRepository;
import org.powertac.visualizer.service.FileService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.hamcrest.Matchers.hasItem;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.powertac.visualizer.domain.enumeration.FileType;

/**
 * Test class for the FileResource REST controller.
 *
 * @see FileResource
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = Visualizer2App.class)
@WebAppConfiguration
@IntegrationTest
public class FileResourceIntTest {


    private static final FileType DEFAULT_TYPE = FileType.TRACE;
    private static final FileType UPDATED_TYPE = FileType.STATE;
    private static final String DEFAULT_NAME = "A";
    private static final String UPDATED_NAME = "B";

    private static final Boolean DEFAULT_SHARED = false;
    private static final Boolean UPDATED_SHARED = true;

    @Inject
    private FileRepository fileRepository;

    @Inject
    private UserRepository userRepository;

    @Inject
    private FileService fileService;

    @Inject
    private MappingJackson2HttpMessageConverter jacksonMessageConverter;

    @Inject
    private PageableHandlerMethodArgumentResolver pageableArgumentResolver;

    private MockMvc restFileMockMvc;

    private File file;

    @PostConstruct
    public void setup() {
        MockitoAnnotations.initMocks(this);
        FileResource fileResource = new FileResource();
        ReflectionTestUtils.setField(fileResource, "fileService", fileService);
        ReflectionTestUtils.setField(fileResource, "userRepository", userRepository);
        this.restFileMockMvc = MockMvcBuilders.standaloneSetup(fileResource)
            .setCustomArgumentResolvers(pageableArgumentResolver)
            .setMessageConverters(jacksonMessageConverter).build();
    }

    @Before
    public void initTest() {
        file = new File();
        file.setType(DEFAULT_TYPE);
        file.setName(DEFAULT_NAME);
        file.setShared(DEFAULT_SHARED);
    }

    @Test
    @Transactional
    public void createFile() throws Exception {
        int databaseSizeBeforeCreate = fileRepository.findAll().size();

        // Create the File

        restFileMockMvc.perform(post("/api/files")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(file)))
                .andExpect(status().isCreated());

        // Validate the File in the database
        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeCreate + 1);
        File testFile = files.get(files.size() - 1);
        assertThat(testFile.getType()).isEqualTo(DEFAULT_TYPE);
        assertThat(testFile.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testFile.isShared()).isEqualTo(DEFAULT_SHARED);
    }

    @Test
    @Transactional
    public void checkTypeIsRequired() throws Exception {
        int databaseSizeBeforeTest = fileRepository.findAll().size();
        // set the field null
        file.setType(null);

        // Create the File, which fails.

        restFileMockMvc.perform(post("/api/files")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(file)))
                .andExpect(status().isBadRequest());

        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeTest);
    }

    @Test
    @Transactional
    public void checkNameIsRequired() throws Exception {
        int databaseSizeBeforeTest = fileRepository.findAll().size();
        // set the field null
        file.setName(null);

        // Create the File, which fails.

        restFileMockMvc.perform(post("/api/files")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(file)))
                .andExpect(status().isBadRequest());

        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeTest);
    }

    @Test
    @Transactional
    public void checkSharedIsRequired() throws Exception {
        int databaseSizeBeforeTest = fileRepository.findAll().size();
        // set the field null
        file.setShared(null);

        // Create the File, which fails.

        restFileMockMvc.perform(post("/api/files")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(file)))
                .andExpect(status().isBadRequest());

        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeTest);
    }

    @Test
    @Transactional
    public void getAllFiles() throws Exception {
        // Initialize the database
        fileRepository.saveAndFlush(file);

        // Get all the files
        restFileMockMvc.perform(get("/api/files?sort=id,desc"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.[*].id").value(hasItem(file.getId().intValue())))
                .andExpect(jsonPath("$.[*].type").value(hasItem(DEFAULT_TYPE.toString())))
                .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME.toString())))
                .andExpect(jsonPath("$.[*].shared").value(hasItem(DEFAULT_SHARED.booleanValue())));
    }

    @Test
    @Transactional
    public void getFile() throws Exception {
        // Initialize the database
        fileRepository.saveAndFlush(file);

        // Get the file
        restFileMockMvc.perform(get("/api/files/{id}", file.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(file.getId().intValue()))
            .andExpect(jsonPath("$.type").value(DEFAULT_TYPE.toString()))
            .andExpect(jsonPath("$.name").value(DEFAULT_NAME.toString()))
            .andExpect(jsonPath("$.shared").value(DEFAULT_SHARED.booleanValue()));
    }

    @Test
    @Transactional
    public void getNonExistingFile() throws Exception {
        // Get the file
        restFileMockMvc.perform(get("/api/files/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    public void updateFile() throws Exception {
        // Initialize the database
        fileService.save(file);

        int databaseSizeBeforeUpdate = fileRepository.findAll().size();

        // Update the file
        File updatedFile = new File();
        updatedFile.setId(file.getId());
        updatedFile.setType(UPDATED_TYPE);
        updatedFile.setName(UPDATED_NAME);
        updatedFile.setShared(UPDATED_SHARED);

        restFileMockMvc.perform(put("/api/files")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(updatedFile)))
                .andExpect(status().isOk());

        // Validate the File in the database
        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeUpdate);
        File testFile = files.get(files.size() - 1);
        assertThat(testFile.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testFile.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testFile.isShared()).isEqualTo(UPDATED_SHARED);
    }

    @Test
    @Transactional
    public void deleteFile() throws Exception {
        // Initialize the database
        fileService.save(file);

        int databaseSizeBeforeDelete = fileRepository.findAll().size();

        // Get the file
        restFileMockMvc.perform(delete("/api/files/{id}", file.getId())
                .accept(TestUtil.APPLICATION_JSON_UTF8))
                .andExpect(status().isOk());

        // Validate the database is empty
        List<File> files = fileRepository.findAll();
        assertThat(files).hasSize(databaseSizeBeforeDelete - 1);
    }
}
