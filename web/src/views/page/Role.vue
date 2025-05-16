<template>
  <a-layout>
    <a-layout-content>
      <div class="layout-content-margin">
        <!-- 查询框 -->
        <div class="table-search">
          <a-form layout="horizontal" :colon="false" :labelCol="{ span: 6 }" :wrapperCol="{ span: 16 }">
            <a-row class="filter-flex">
              <a-col :xl="6" :lg="12" :xs="24" v-for="item in queryFilter" :key="item.index">
                <a-form-item :label="item.label">
                  <a-input-search v-model="query[item.index]" placeholder="请输入" allow-clear @search="getData()" />
                </a-form-item>
              </a-col>
            </a-row>
          </a-form>
        </div>
        <!-- 表格数据 -->
        <a-card :bodyStyle="{ padding: 0 }" :bordered="false">
          <a-tabs defaultActiveKey="1" :activeKey="activeTabKey" @change="handleTabChange"
            tabBarStyle="margin: 0 0 0 15px">
            <a-tab-pane key="1" tab="角色列表">
              <a-table :columns="columns" :dataSource="roleItems" :loading="loading" :pagination="pagination"
                rowKey="roleId" :scroll="{ x: 800 }" size="middle">
                <template slot="roleDesc" slot-scope="text, record">
                  <a-tooltip :title="text" :mouseEnterDelay="0.5" placement="leftTop">
                    <span v-if="text">{{ text }}</span>
                    <span v-else style="padding: 0 50px">&nbsp;&nbsp;&nbsp;</span>
                  </a-tooltip>
                </template>
                <!-- 添加默认状态列的自定义渲染 -->
                <template slot="isDefault" slot-scope="text">
                  <a-tag v-if="text == 1" color="green">默认</a-tag>
                  <span v-else>-</span>
                </template>
                <template slot="operation" slot-scope="text, record">
                  <a-space>
                    <a @click="edit(record)">编辑</a>
                    <!-- 添加设为默认按钮 -->
                    <a v-if="record.isDefault != 1" href="javascript:" :disabled="record.isDefault == 1"
                      @click="setAsDefault(record)">设为默认</a>
                    <a-popconfirm title="确定要删除这个角色吗?" @confirm="update(record.roleId, '0')">
                      <a href="javascript:" style="color: #ff4d4f">删除</a>
                    </a-popconfirm>
                  </a-space>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="2" tab="创建角色">
              <a-form layout="horizontal" :form="roleForm" :colon="false" @submit="handleSubmit"
                style="padding: 10px 24px">
                <a-row :gutter="20">
                  <a-col :xl="8" :lg="12" :xs="24">
                    <a-form-item label="角色名称">
                      <a-input v-decorator="[
                        'roleName',
                        {
                          rules: [
                            { required: true, message: '请输入角色名称' },
                          ],
                        },
                      ]" autocomplete="off" placeholder="请输入角色名称" />
                    </a-form-item>
                  </a-col>
                </a-row>

                <!-- 添加是否默认的开关 -->
                <a-form-item label="设为默认角色">
                  <a-switch v-decorator="[
                    'isDefault',
                    { valuePropName: 'checked', initialValue: false },
                  ]" />
                  <span style="margin-left: 8px; color: #999">设为默认后将优先使用此角色</span>
                </a-form-item>

                <a-divider>语音设置</a-divider>

                <!-- 语音设置区域 -->
                <a-space direction="vertical" size="large" style="width: 100%">
                  <a-row :gutter="20">
                    <!-- 新增语音提供商选择 -->
                    <a-col :xl="6" :lg="12" :xs="24">
                      <a-form-item label="语音提供商">
                        <a-select v-decorator="['provider', { initialValue: 'edge' }]" placeholder="请选择语音提供商"
                          @change="handleProviderChange">
                          <a-select-option value="edge">微软Edge</a-select-option>
                          <a-select-option value="aliyun">阿里云</a-select-option>
                          <a-select-option value="volcengine">火山引擎（豆包）</a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                    <!-- TTS配置选择，现在对所有提供商都显示 -->
                    <a-col :xl="6" :lg="12" :xs="24">
                      <a-form-item label="TTS配置">
                        <a-select v-decorator="[
                          'ttsId',
                          { rules: [{ required: true, message: '请选择TTS配置' }] }
                        ]" placeholder="请选择TTS配置" @change="handleTtsConfigChange" :loading="ttsConfigLoading">
                          <!-- 为Edge提供默认配置 -->
                          <a-select-option v-if="selectedProvider === 'edge'" value="edge_default">
                            默认配置
                          </a-select-option>
                          <!-- 为其他提供商显示动态配置 -->
                          <a-select-option v-for="config in ttsConfigs" :key="config.configId" :value="config.configId">
                            {{ config.configName }}
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                    <a-col :xl="6" :lg="12" :xs="24" v-if="selectedTtsId !== 'voice_clone'">
                      <a-form-item label="语音性别">
                        <a-select v-decorator="['gender', { initialValue: '' }]" placeholder="请选择语音性别"
                          @change="handleGenderChange">
                          <a-select-option value="">不限</a-select-option>
                          <a-select-option value="male">男声</a-select-option>
                          <a-select-option value="female">女声</a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                    <a-col :xl="6" :lg="12" :xs="24">
                      <a-form-item label="语音名称">
                        <a-select v-decorator="[
                          'voiceName',
                          {
                            initialValue: defaultVoiceName,
                            rules: [{ required: true, message: '请选择语音名称' }]
                          }
                        ]" placeholder="请选择语音名称" :loading="voiceLoading">
                          <a-select-option v-for="voice in filteredVoices" :key="voice.value" :value="voice.value">
                            {{ voice.label }}
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                    <a-col :xl="12" :lg="12" :xs="24">
                      <a-form-item label="语音测试">
                        <a-input-search v-model="testText" placeholder="请输入要测试的文本" enter-button="测试"
                          :loading="audioTesting" @search="testVoice" />
                      </a-form-item>
                    </a-col>
                  </a-row>

                  <!-- 音频播放器 -->
                  <a-card v-if="audioUrl" size="small" :bordered="false">
                    <AudioPlayer :audioUrl="audioUrl" :autoPlay="true" />
                  </a-card>
                </a-space>

                <a-divider>角色提示词(Prompt)</a-divider>

                <a-space direction="vertical" style="width: 100%">
                  <!-- 在提示词编辑区域添加模板管理按钮 -->
                  <a-form-item>
                    <div
                      style="margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center">
                      <a-space>
                        <a-radio-group v-model="promptEditorMode" button-style="solid" @change="handlePromptModeChange">
                          <a-radio-button value="template">使用模板</a-radio-button>
                          <a-radio-button value="custom">自定义</a-radio-button>
                        </a-radio-group>

                        <template v-if="promptEditorMode === 'template'">
                          <a-select style="width: 200px" placeholder="选择模板" v-model="selectedTemplateId"
                            @change="handleTemplateChange" :loading="templatesLoading">
                            <a-select-option v-for="template in promptTemplates" :key="template.templateId"
                              :value="template.templateId">
                              {{ template.templateName }}
                              <a-tag v-if="template.isDefault == 1" color="green" size="small">默认</a-tag>
                            </a-select-option>
                          </a-select>
                        </template>
                      </a-space>

                      <!-- 添加模板管理按钮 -->
                      <a-button type="primary" @click="goToTemplateManager">
                        <a-icon type="snippets" /> 模板管理
                      </a-button>
                    </div>

                    <a-textarea v-decorator="[
                      'roleDesc',
                      {
                        rules: [],
                      },
                    ]" :rows="10" placeholder="请输入角色提示词，描述角色的特点、知识背景和行为方式等" />
                  </a-form-item>

                  <a-form-item>
                    <a-button type="primary" html-type="submit">
                      {{ editingRoleId ? '更新角色' : '创建角色' }}
                    </a-button>
                    <a-button style="margin-left: 8px" @click="resetForm">
                      取消
                    </a-button>
                  </a-form-item>
                </a-space>
              </a-form>
            </a-tab-pane>
          </a-tabs>
        </a-card>
      </div>
    </a-layout-content>
  </a-layout>
</template>

<script>
import axios from '@/services/axios'
import api from '@/services/api'
import mixin from '@/mixins/index'
import AudioPlayer from '@/components/AudioPlayer.vue'

export default {
  mixins: [mixin],
  components: {
    AudioPlayer
  },
  data() {
    return {
      // 查询框
      query: {
        state: "",
      },
      queryFilter: [
        {
          label: "角色名称",
          value: "",
          index: "roleName",
        },
      ],
      // 语音相关
      edgeVoices: [],
      aliyunVoices: [],
      volcengineVoices: [], // 新增火山引擎语音列表
      voiceLoading: false, // 语音列表加载状态
      selectedProvider: 'edge', // 默认使用Edge语音
      selectedGender: '', // 存储当前选择的性别
      activeTabKey: '1', // 当前激活的标签页
      roleForm: this.$form.createForm(this),
      roleItems: [],
      editingRoleId: null,
      columns: [
        {
          title: '角色名称',
          dataIndex: 'roleName',
          key: 'roleName',
          width: 150,
          align: 'center'
        },
        {
          title: '角色描述',
          dataIndex: 'roleDesc',
          scopedSlots: { customRender: 'roleDesc' },
          key: 'roleDesc',
          align: 'center',
          ellipsis: true
        },
        // 添加默认标识列
        {
          title: '默认',
          dataIndex: 'isDefault',
          key: 'isDefault',
          width: 80,
          align: 'center',
          scopedSlots: { customRender: 'isDefault' }
        },
        {
          title: '创建时间',
          dataIndex: 'createTime',
          key: 'createTime',
          width: 180,
          align: 'center'
        },
        {
          title: '操作',
          dataIndex: 'operation',
          key: 'operation',
          width: 220,
          align: 'center',
          fixed: 'right',
          scopedSlots: { customRender: 'operation' }
        }
      ],
      templatesLoading: false, // 模板加载状态
      selectedTemplateId: null, // 当前选择的模板ID
      // 提示词编辑器模式
      promptEditorMode: "custom",
      // 音频测试相关
      audioTesting: false,
      audioUrl: '',
      testText: '你好，我是小智，很高兴为您服务。',
      // TTS配置相关
      ttsConfigs: [], // 存储TTS配置列表
      ttsConfigLoading: false, // TTS配置加载状态
      selectedTtsId: null, // 当前选择的TTS配置ID
      promptTemplates: [], // 提示词模板列表
    }
  },
  mounted() {
    this.loadEdgeVoices();
    this.loadAliyunVoices();
    this.loadVolcengineVoices();

    this.getData();

    // 初始化设置Edge默认TTS配置
    this.selectedTtsId = "edge_default";

    // 加载提示词模板列表
    this.loadTemplates();
  },
  computed: {
    // 获取当前选中提供商的语音列表
    allVoices() {
      if (this.selectedProvider === 'edge') {
        return this.edgeVoices;
      } else if (this.selectedProvider === 'aliyun') {
        return this.aliyunVoices;
      } else if (this.selectedProvider === 'volcengine') {
        return this.volcengineVoices;
      }
      return [];
    },
    filteredVoices() {
      // 根据选择的性别筛选语音选项
      if (!this.selectedGender) {
        return this.allVoices; // 如果没有选择性别或选择"不限"，返回所有语音
      } else {
        return this.allVoices.filter(voice => voice.gender === this.selectedGender);
      }
    },
    // 计算默认的语音名称（第一个可用的语音）
    defaultVoiceName() {
      if (this.filteredVoices && this.filteredVoices.length > 0) {
        return this.filteredVoices[0].value;
      }
      return undefined;
    }
  },
  methods: {  
    // 处理标签页切换
    handleTabChange(key) {
      this.activeTabKey = key;
      this.resetForm();
    },

    // 处理模板选择变化
    handleTemplateChange(templateId) {
      const template = this.promptTemplates.find(t => t.templateId === templateId);
      if (template) {
        this.roleForm.setFieldsValue({
          roleDesc: template.templateContent
        });
      }
    },

    // 处理提示词编辑模式变化
    handlePromptModeChange(e) {
      if (e.target.value === 'template') {
        // 切换到模板模式，加载模板列表
        if (this.promptTemplates.length === 0) {
          this.loadTemplates();
        } else if (this.selectedTemplateId) {
          // 如果已经选择了模板，应用该模板
          const template = this.promptTemplates.find(t => t.templateId === this.selectedTemplateId);
          if (template) {
            this.roleForm.setFieldsValue({
              roleDesc: template.templateContent
            });
          }
        }
      }
    },

    // 跳转到模板管理页面
    goToTemplateManager() {
      this.$router.push('/prompt-template');
    },

    // 设置为默认角色
    setAsDefault(record) {
      this.$confirm({
        title: '确定要将此角色设为默认吗？',
        content: '设为默认后，系统将优先使用此角色，原默认角色将被取消默认状态。',
        okText: '确定',
        cancelText: '取消',
        onOk: () => {
          this.loading = true;
          
          // 调用后端API更新角色为默认
          axios.post({
            url: api.role.update,
            data: {
              roleId: record.roleId,
              isDefault: 1
            }
          })
            .then(res => {
              if (res.code === 200) {
                this.$message.success(`已将"${record.roleName}"设为默认角色`);
                this.getData();
              } else {
                this.$message.error(res.message || '设置默认角色失败');
              }
            })
            .catch(error => {
              this.$message.error('设置默认角色失败');
            })
            .finally(() => {
              this.loading = false;
            });
        }
      });
    },

    // 处理语音提供商选择变化
    handleProviderChange(value) {
      this.selectedProvider = value;
      
      // 清空当前语音名称和TTS配置，等待加载完成后再设置
      this.roleForm.setFieldsValue({
        voiceName: undefined,
        ttsId: undefined // 清空TTS配置选择
      });
      
      // 清空当前TTS配置列表，避免显示上一个提供商的配置
      this.ttsConfigs = [];
      this.selectedTtsId = null;

      // 根据提供商设置TTS配置
      if (value === 'edge') {
        // 为Edge提供默认配置
        this.ttsConfigs = [];
        this.selectedTtsId = 'edge_default';
        this.$nextTick(() => {
          this.roleForm.setFieldsValue({
            ttsId: 'edge_default'
          });
        });
      } else {
        // 加载TTS配置
        this.loadTtsConfigs(value);
      }

      // 重置性别选择
      this.selectedGender = '';
      this.roleForm.setFieldsValue({
        gender: ''
      });
      
      // 在下一个渲染循环更新语音名称选择
      this.$nextTick(() => {
        if (this.filteredVoices && this.filteredVoices.length > 0) {
          this.roleForm.setFieldsValue({
            voiceName: this.filteredVoices[0].value
          });
        }
      });
    },

    // 处理TTS配置选择变化
    handleTtsConfigChange(value) {
      this.selectedTtsId = value;
    },

    // 加载TTS配置列表
    loadTtsConfigs(provider) {
      // 设置加载状态
      this.ttsConfigLoading = true;
      this.ttsConfigs = []; // 清空当前配置，避免显示上一个提供商的配置
      this.selectedTtsId = null; // 清空当前选中的TTS配置ID
      
      // 清空表单中的TTS配置选择
      this.$nextTick(() => {
        this.roleForm.setFieldsValue({
          ttsId: undefined
        });
      });
      
      axios
        .get({
          url: api.config.query,
          data: {
            configType: 'tts',
            provider: provider,
            state: '1' // 只查询可用的配置
          }
        })
        .then(res => {
          if (res.code === 200) {
            const configList = res.data.list || [];
            this.ttsConfigs = configList;
            
            // 如果有配置，默认选择第一个
            if (configList.length > 0) {
              this.selectedTtsId = configList[0].configId;
              this.$nextTick(() => {
                this.roleForm.setFieldsValue({
                  ttsId: this.selectedTtsId
                });
              });
            } else {
              this.$message.warning(`没有找到可用的 ${provider} 语音合成配置，请先在语音合成配置中添加`);
              this.selectedTtsId = null;
            }
          } else {
            this.$message.error(res.message);
          }
        })
        .catch(() => {
          this.$message.error('加载TTS配置失败，请稍后再试');
        })
        .finally(() => {
          this.ttsConfigLoading = false;
        });
    },

    // 处理性别选择变化
    handleGenderChange(value) {
      this.selectedGender = value;

      // 当性别变化时，设置语音名称为新的默认值（该性别的第一个语音）
      this.$nextTick(() => {
        if (this.filteredVoices && this.filteredVoices.length > 0) {
          this.roleForm.setFieldsValue({
            voiceName: this.filteredVoices[0].value
          });
        } else {
          this.roleForm.setFieldsValue({
            voiceName: undefined
          });
        }
      });
    },

    // 获取角色列表
    getData() {
      this.loading = true;
      axios
        .get({
          url: api.role.query,
          data: {
            start: this.pagination.page,
            limit: this.pagination.pageSize,
            ...this.query
          }
        })
        .then(res => {
          if (res.code === 200) {
            this.roleItems = res.data.list;
            this.pagination.total = res.data.total;
          } else {
            this.$message.error(res.message);
          }
        })
        .catch(() => {
          this.$message.error('服务器维护/重启中，请稍后再试');
        })
        .finally(() => {
          this.loading = false;
        });
    },

    // 加载Edge语音列表
    loadEdgeVoices() {
      this.voiceLoading = true;
      
      fetch('/static/assets/edgeVoicesList.json')
        .then(response => {
          if (!response.ok) {
            throw new Error('加载Edge语音列表失败');
          }
          return response.json();
        })
        .then(data => {
          // 提取中文语音列表
          const voices = data.filter(voice => voice.Locale.includes('zh'))
            .sort((a, b) => a.Locale.localeCompare(b.Locale))
            .map(voice => {
              // 从ShortName中提取名称部分 (如从"zh-TW-HsiaoYuNeural"提取"HsiaoYu")
              const nameParts = voice.ShortName.split('-');
              let name = nameParts[2];

              // 移除Neural后缀
              if (name.endsWith('Neural')) {
                name = name.substring(0, name.length - 6);
              }

              // 获取区域代码
              const locale = voice.Locale;
              return {
                label: `${name} (${locale})`,
                value: voice.ShortName,
                gender: voice.Gender.toLowerCase(),
                provider: 'edge'
              };
            });

          // 保存语音列表
          this.edgeVoices = voices;

          // 加载完语音列表后，如果当前选择的是Edge，设置默认语音
          this.$nextTick(() => {
            if (this.selectedProvider === 'edge' && this.edgeVoices.length > 0 && this.activeTabKey === '2') {
              this.roleForm.setFieldsValue({
                voiceName: this.defaultVoiceName
              });
            }
          });
        })
        .catch(error => {
          this.$message.error('加载Edge语音列表失败，请刷新页面重试');
        })
        .finally(() => {
          this.voiceLoading = false;
        });
    },

    // 加载阿里云语音列表 - 从本地文件加载
    loadAliyunVoices() {
      this.voiceLoading = true;
      
      // 直接从本地文件加载阿里云语音列表
      fetch('/static/assets/aliyunVoicesList.json')
        .then(response => {
          if (!response.ok) {
            throw new Error('加载阿里云语音列表失败');
          }
          return response.json();
        })
        .then(voices => {
          // 保存语音列表
          this.aliyunVoices = voices;

          // 加载完语音列表后，如果当前选择的是阿里云，设置默认语音
          this.$nextTick(() => {
            if (this.selectedProvider === 'aliyun' && this.aliyunVoices.length > 0 && this.activeTabKey === '2') {
              this.roleForm.setFieldsValue({
                voiceName: this.defaultVoiceName
              });
            }
          });
        })
        .catch(error => {
          this.$message.error('加载阿里云语音列表失败，请确认文件是否存在');
        })
        .finally(() => {
          this.voiceLoading = false;
        });
    },

    // 加载火山引擎语音列表 - 从本地文件加载
    loadVolcengineVoices() {
      this.voiceLoading = true;
      
      // 直接从本地文件加载火山引擎语音列表
      fetch('/static/assets/volcengineVoicesList.json')
        .then(response => {
          if (!response.ok) {
            throw new Error('加载火山引擎语音列表失败');
          }
          return response.json();
        })
        .then(voices => {
          // 保存语音列表
          this.volcengineVoices = voices;

          // 加载完语音列表后，如果当前选择的是火山引擎，设置默认语音
          this.$nextTick(() => {
            if (this.selectedProvider === 'volcengine' && this.volcengineVoices.length > 0 && this.activeTabKey === '2') {
              this.roleForm.setFieldsValue({
                voiceName: this.defaultVoiceName
              });
            }
          });
        })
        .catch(error => {
          this.$message.error('加载火山引擎语音列表失败，请确认文件是否存在');
        })
        .finally(() => {
          this.voiceLoading = false;
        });
    },

    // 提交表单
    handleSubmit(e) {
      e.preventDefault();
      this.roleForm.validateFields((err, values) => {
        if (!err) {
          this.loading = true;

          // 添加语音提供商信息
          const formData = {
            ...values,
            provider: this.selectedProvider,
            // 将开关的布尔值转换为数字（0或1）
            isDefault: values.isDefault ? 1 : 0
          };

          // 为所有提供商传递TTS配置ID
          if (this.selectedTtsId) {
            formData.ttsId = this.selectedTtsId;
          }

          const url = this.editingRoleId
            ? api.role.update
            : api.role.add;

          axios
            .post({
              url,
              data: {
                roleId: this.editingRoleId,
                ...formData,
                ttsId: formData.provider === 'edge' ? -1 : formData.ttsId
              }
            })
            .then(res => {
              if (res.code === 200) {
                this.$message.success(
                  this.editingRoleId ? '更新成功' : '创建成功'
                );
                this.resetForm();
                this.getData();
                // 成功后切换到角色列表页
                this.activeTabKey = '1';
              } else {
                this.$message.error(res.message);
              }
            })
            .catch(() => {
              this.$message.error('服务器维护/重启中，请稍后再试');
            })
            .finally(() => {
              this.loading = false;
            });
        }
      });
    },

    // 编辑角色
    edit(record) {
      this.editingRoleId = record.roleId;

      // 切换到创建角色标签页
      this.activeTabKey = '2';

      this.$nextTick(() => {
        const { roleForm } = this;

        // 设置语音提供商
        this.selectedProvider = record.provider || 'edge';

        // 设置TTS配置ID
        if (this.selectedProvider === 'edge') {
          this.selectedTtsId = record.ttsId || 'edge_default';
          this.ttsConfigs = [];
          this.$nextTick(() => {
            roleForm.setFieldsValue({
              ttsId: 'edge_default'
            });
          });
        } else {
          // 加载TTS配置
          this.loadTtsConfigs(this.selectedProvider);
          // 设置要在加载完成后选中的TTS配置ID
          this.selectedTtsId = record.ttsId;
        }

        // 设置当前选择的性别，以便正确筛选语音
        this.selectedGender = record.gender || '';

        // 设置表单值，将isDefault从数字转为布尔值
        roleForm.setFieldsValue({
          ...record,
          provider: this.selectedProvider,
          gender: this.selectedGender,
          isDefault: record.isDefault == 1
        });
      });
    },

    // 删除/禁用角色
    update(roleId, state) {
      this.loading = true;
      axios
        .post({
          url: api.role.update,
          data: {
            roleId: roleId,
            state: state
          }
        })
        .then(res => {
          if (res.code === 200) {
            this.$message.success('操作成功');
            this.getData();
          } else {
            this.$message.error(res.message);
          }
        })
        .catch(() => {
          this.$message.error('服务器维护/重启中，请稍后再试');
        })
        .finally(() => {
          this.loading = false;
        });
    },

    // 重置表单
    resetForm() {
      this.roleForm.resetFields();
      this.editingRoleId = null;
      this.promptEditorMode = 'custom';
      this.audioUrl = '';
      this.selectedGender = ''; // 重置性别选择
      this.selectedProvider = 'edge'; // 重置为默认语音提供商
      
      // 清空TTS配置
      this.ttsConfigs = [];
      
      // 设置Edge默认TTS配置
      this.selectedTtsId = 'edge_default';

      // 重置后设置默认语音
      this.$nextTick(() => {
        this.roleForm.setFieldsValue({
          provider: 'edge',
          gender: '',
          ttsId: 'edge_default',
          voiceName: this.defaultVoiceName,
          isDefault: false
        });
      });
    },

    // 加载提示词模板列表
    loadTemplates() {
      this.templatesLoading = true;

      axios.get({
        url: api.template.query,
        data: {}
      })
        .then(res => {
          if (res.code === 200) {
            this.promptTemplates = res.data.list;
            // 如果有默认模板，自动选择
            const defaultTemplate = this.promptTemplates.find(t => t.isDefault == 1);
            if (defaultTemplate) {
              this.selectedTemplateId = defaultTemplate.templateId;
              if (this.promptEditorMode === 'template') {
                this.roleForm.setFieldsValue({
                  roleDesc: defaultTemplate.templateContent
                });
              }
            }
          } else {
            this.$message.error(res.message);
          }
        })
        .catch(() => {
          this.$message.error("获取模板列表失败");
        })
        .finally(() => {
          this.templatesLoading = false;
        });
    },

    // 测试语音
    testVoice() {
      if (!this.testText.trim()) {
        this.$message.warning('请输入测试文本');
        return;
      }

      this.roleForm.validateFields(['voiceName', 'ttsId'], (err, values) => {
        if (err) {
          return;
        }
        
        this.audioTesting = true;
        
        // 构建请求参数
        const requestData = {
          voiceName: values.voiceName,
          provider: this.selectedProvider,
          message: this.testText,
          ttsId: this.selectedProvider === 'edge' ? -1 : this.selectedTtsId
        };

        axios
          .get({
            url: api.role.testVoice,
            data: requestData
          }).then(res => {
            if (res.code === 200) {
              this.audioUrl = res.data;
            } else {
              this.$message.error(res.message);
            }
          }).catch((e) => {
            this.$message.error('语音合成失败，请稍后再试');
          }).finally(() => {
            this.audioTesting = false;
          });
      });
    },
  }
}
</script>